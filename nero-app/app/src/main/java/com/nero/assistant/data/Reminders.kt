package com.nero.assistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Reminder(
    val id: Long,
    val title: String,
    val timeMillis: Long,
)

/** Guarda os lembretes no aparelho. */
class ReminderStore(context: Context) {

    private val prefs = context.getSharedPreferences("nero_reminders", Context.MODE_PRIVATE)

    fun all(): List<Reminder> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Reminder(o.getLong("id"), o.getString("title"), o.getLong("time"))
        }.sortedBy { it.timeMillis }
    }.getOrDefault(emptyList())

    fun upcoming(now: Long = System.currentTimeMillis()) = all().filter { it.timeMillis >= now - 60_000 }

    fun get(id: Long) = all().firstOrNull { it.id == id }

    fun add(title: String, timeMillis: Long): Reminder {
        val reminder = Reminder(System.currentTimeMillis(), title.trim(), timeMillis)
        save(all() + reminder)
        return reminder
    }

    fun remove(id: Long) = save(all().filterNot { it.id == id })

    private fun save(list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("time", it.timeMillis)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "reminders"
    }
}

/**
 * Lê o bloco de lembrete que a IA escreve no fim da resposta, no formato
 * `[[LEMBRETE {"titulo": "...", "quando": "2026-10-07T15:00"}]]`.
 */
object ReminderParser {

    private val BLOCK = Regex("""\[\[LEMBRETE\s*(\{.*?\})\s*]]""", RegexOption.DOT_MATCHES_ALL)
    private val TOKEN = Regex("""\[\[lembrete:(\d+)]]""")

    data class Request(val title: String, val timeMillis: Long)

    /** Devolve o texto sem o bloco e o pedido de lembrete encontrado (se for válido). */
    fun extract(text: String, timeZone: TimeZone = TimeZone.getDefault()): Pair<String, Request?> {
        val match = BLOCK.find(text) ?: return text to null
        val clean = text.removeRange(match.range).trimEnd()
        val request = runCatching {
            val json = JSONObject(match.groupValues[1])
            val title = json.getString("titulo").trim()
            val time = parseTime(json.getString("quando"), timeZone)
            if (title.isEmpty() || time == null) null else Request(title, time)
        }.getOrNull()
        return clean to request
    }

    /** Marca no texto salvo qual lembrete foi criado, para a tela mostrar o cartão. */
    fun token(id: Long) = "[[lembrete:$id]]"

    fun tokenIds(text: String): List<Long> = TOKEN.findAll(text).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()

    fun stripTokens(text: String) = text.replace(TOKEN, "").trimEnd()

    /** Esconde um bloco ainda incompleto enquanto a resposta chega em streaming. */
    fun hidePartial(text: String): String {
        val start = text.lastIndexOf("[[")
        if (start < 0) return text
        val tail = text.substring(start)
        val isBlock = tail.startsWith(MARKER) || MARKER.startsWith(tail)
        return if (isBlock) text.substring(0, start).trimEnd() else text
    }

    private const val MARKER = "[[LEMBRETE"

    private fun parseTime(value: String, timeZone: TimeZone): Long? {
        val v = value.trim()
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss")) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                this.timeZone = timeZone
                isLenient = false
            }
            runCatching { return format.parse(v)?.time }
        }
        return null
    }

    /** Instrução extra enviada ao modelo para ele saber criar lembretes. */
    fun instructions(now: Date = Date(), timeZone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat("EEEE, yyyy-MM-dd'T'HH:mm", Locale("pt", "BR")).apply { this.timeZone = timeZone }
        return """
            Agora é ${format.format(now)} (fuso ${timeZone.id}).
            Você pode criar lembretes no aparelho do usuário. Quando ele pedir para lembrar, agendar,
            marcar ou anotar algo com data ou horário (inclusive se a informação vier de uma imagem),
            responda normalmente confirmando e termine a resposta com exatamente uma linha:
            [[LEMBRETE {"titulo": "texto curto do lembrete", "quando": "AAAA-MM-DDTHH:MM"}]]
            Use horário local de 24h. Se faltar o horário, use 09:00. Se faltar a data, pergunte antes
            e não escreva a linha. Nunca escreva essa linha em outras situações.
        """.trimIndent()
    }

    /** Formato amigável para mostrar a data do lembrete. */
    fun describe(timeMillis: Long, now: Long = System.currentTimeMillis()): String {
        val target = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        val today = Calendar.getInstance().apply { this.timeInMillis = now }
        val hour = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(timeMillis))
        val dayDiff = daysBetween(today, target)
        val day = when (dayDiff) {
            0 -> "Hoje"
            1 -> "Amanhã"
            else -> SimpleDateFormat("EEE, d 'de' MMM", Locale("pt", "BR")).format(Date(timeMillis))
                .replaceFirstChar { it.uppercase() }
        }
        return "$day às $hour"
    }

    private fun daysBetween(a: Calendar, b: Calendar): Int {
        fun dayStart(c: Calendar) = (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return Math.round((dayStart(b) - dayStart(a)) / 86_400_000.0).toInt()
    }
}
