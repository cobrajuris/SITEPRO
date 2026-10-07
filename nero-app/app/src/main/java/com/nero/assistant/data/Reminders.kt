package com.nero.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Com que frequência o lembrete volta. [key] é o valor usado no banco e pela IA. */
enum class Repeat(val key: String, val label: String) {
    NONE("", "Não repete"),
    DAILY("diario", "Todo dia"),
    WEEKLY("semanal", "Toda semana"),
    MONTHLY("mensal", "Todo mês");

    companion object {
        fun from(value: String?): Repeat {
            val clean = Normalizer.normalize(value.orEmpty().trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
            return entries.firstOrNull { it.key.isNotEmpty() && it.key == clean } ?: when (clean) {
                "daily", "diariamente", "todo dia", "todos os dias" -> DAILY
                "weekly", "semanalmente", "toda semana" -> WEEKLY
                "monthly", "mensalmente", "todo mes" -> MONTHLY
                else -> NONE
            }
        }
    }
}

data class Reminder(
    val id: Long,
    val title: String,
    val timeMillis: Long,
    val repeat: Repeat = Repeat.NONE,
    val done: Boolean = false,
    /** true: toca o alarme do Nero (som, vibração e tela cheia); false: só notificação. */
    val alarm: Boolean = true,
    /** Quantos minutos antes do horário o aviso dispara. */
    val leadMinutes: Int = 0,
    /** Evento correspondente no calendário do celular (null se não foi salvo lá). */
    val eventId: Long? = null,
) {
    /** Quando o aviso deve tocar (o horário menos a antecedência). */
    val alertAt get() = timeMillis - leadMinutes * 60_000L
}

/** Opções de antecedência oferecidas no editor. */
val LEAD_OPTIONS = listOf(0, 5, 15, 30, 60, 120, 1440)

/** Maior antecedência aceita: uma semana. */
const val MAX_LEAD_MINUTES = 7 * 24 * 60

fun leadLabel(minutes: Int) = when {
    minutes <= 0 -> "Na hora"
    minutes == 1440 -> "1 dia antes"
    minutes % 1440 == 0 -> "${minutes / 1440} dias antes"
    minutes % 60 == 0 -> "${minutes / 60} h antes"
    else -> "$minutes min antes"
}

/** Contas de datas dos lembretes que se repetem (sem depender do Android, para dar para testar). */
object Recurrence {

    /** Horário da [k]-ésima repetição a partir de [start] (k = 0 é o próprio [start]). */
    fun nth(start: Long, repeat: Repeat, k: Int, timeZone: TimeZone = TimeZone.getDefault()): Long {
        if (repeat == Repeat.NONE || k == 0) return start
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = start
            when (repeat) {
                Repeat.DAILY -> add(Calendar.DAY_OF_MONTH, k)
                Repeat.WEEKLY -> add(Calendar.WEEK_OF_YEAR, k)
                // Sempre a partir do início, para 31/jan → 28/fev → 31/mar e não ir "encolhendo".
                Repeat.MONTHLY -> add(Calendar.MONTH, k)
                Repeat.NONE -> Unit
            }
        }.timeInMillis
    }

    /** Primeira repetição depois de [after]; para lembretes que não repetem devolve null. */
    fun next(start: Long, repeat: Repeat, after: Long, timeZone: TimeZone = TimeZone.getDefault()): Long? {
        if (repeat == Repeat.NONE) return null
        var k = 1
        while (true) {
            val t = nth(start, repeat, k, timeZone)
            if (t > after) return t
            k++
        }
    }

    /** Todas as vezes que o lembrete acontece dentro de [from, to). */
    fun occurrences(reminder: Reminder, from: Long, to: Long, timeZone: TimeZone = TimeZone.getDefault()): List<Long> {
        if (reminder.repeat == Repeat.NONE || reminder.done) {
            return if (reminder.timeMillis in from until to) listOf(reminder.timeMillis) else emptyList()
        }
        val out = mutableListOf<Long>()
        var k = 0
        while (out.size < 400) {
            val t = nth(reminder.timeMillis, reminder.repeat, k, timeZone)
            if (t >= to) break
            if (t >= from) out += t
            k++
        }
        return out
    }
}

/** Banco SQLite dos lembretes (só usa o que já vem no Android). */
private class ReminderDb(context: Context) : SQLiteOpenHelper(context, "nero.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE reminders (id INTEGER PRIMARY KEY, title TEXT NOT NULL, time INTEGER NOT NULL, " +
                "repeat TEXT NOT NULL DEFAULT '', done INTEGER NOT NULL DEFAULT 0, " +
                "alarm INTEGER NOT NULL DEFAULT 1, lead INTEGER NOT NULL DEFAULT 0, event_id INTEGER)"
        )
        db.execSQL("CREATE INDEX reminders_time ON reminders(time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versão 2 (Nero 1.7): alarme próprio e antecedência.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE reminders ADD COLUMN alarm INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE reminders ADD COLUMN lead INTEGER NOT NULL DEFAULT 0")
        }
        // Versão 3 (Nero 1.8): ligação com o evento salvo no calendário do celular.
        if (oldVersion < 3) db.execSQL("ALTER TABLE reminders ADD COLUMN event_id INTEGER")
    }
}

/** Guarda os lembretes no aparelho. */
class ReminderStore(context: Context) {

    private val db = ReminderDb(context.applicationContext)
    private val settings = context.getSharedPreferences("nero_alarm", Context.MODE_PRIVATE)

    /** Salvar os compromissos no calendário do celular automaticamente? */
    var calendarSync: Boolean
        get() = settings.getBoolean("calendar_sync", true)
        set(value) = settings.edit().putBoolean("calendar_sync", value).apply()

    /** Som escolhido para o alarme (vazio = o alarme padrão do celular). */
    var alarmSound: String
        get() = settings.getString("alarm_sound", "").orEmpty()
        set(value) = settings.edit().putString("alarm_sound", value).apply()

    init {
        // A versão 1.4/1.5 guardava os lembretes em SharedPreferences: copia para o banco uma única vez.
        val prefs = context.getSharedPreferences("nero_reminders", Context.MODE_PRIVATE)
        prefs.getString(OLD_KEY, null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                db.writableDatabase.run {
                    beginTransaction()
                    try {
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            insertWithOnConflict(TABLE, null, values(Reminder(o.getLong("id"), o.getString("title"), o.getLong("time"))),
                                SQLiteDatabase.CONFLICT_IGNORE)
                        }
                        setTransactionSuccessful()
                    } finally {
                        endTransaction()
                    }
                }
            }
            prefs.edit().remove(OLD_KEY).apply()
        }
    }

    fun all(): List<Reminder> = query(null, null)

    /** Lembretes ainda por vir (os concluídos ficam de fora). */
    fun upcoming(now: Long = System.currentTimeMillis()) =
        query("done = 0 AND time >= ?", arrayOf((now - 60_000).toString()))

    fun get(id: Long) = query("id = ?", arrayOf(id.toString())).firstOrNull()

    fun add(
        title: String,
        timeMillis: Long,
        repeat: Repeat = Repeat.NONE,
        alarm: Boolean = true,
        leadMinutes: Int = 0,
    ): Reminder {
        val last = db.readableDatabase.rawQuery("SELECT MAX(id) FROM $TABLE", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
        val reminder = Reminder(
            maxOf(System.currentTimeMillis(), last + 1), title.trim(), timeMillis, repeat, false, alarm,
            leadMinutes.coerceIn(0, MAX_LEAD_MINUTES),
        )
        db.writableDatabase.insert(TABLE, null, values(reminder))
        return reminder
    }

    fun update(reminder: Reminder) {
        db.writableDatabase.update(TABLE, values(reminder), "id = ?", arrayOf(reminder.id.toString()))
    }

    fun remove(id: Long) {
        db.writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))
    }

    /**
     * Marca como feito. Lembretes que se repetem não terminam: pulam para a próxima vez.
     * Devolve o lembrete atualizado.
     */
    fun complete(id: Long, now: Long = System.currentTimeMillis()): Reminder? {
        val reminder = get(id) ?: return null
        val next = Recurrence.next(reminder.timeMillis, reminder.repeat, maxOf(now, reminder.timeMillis))
        val updated = if (next != null) reminder.copy(timeMillis = next) else reminder.copy(done = true)
        update(updated)
        return updated
    }

    /** Cada vez que algum lembrete acontece no intervalo, já com as repetições expandidas. */
    fun occurrencesBetween(from: Long, to: Long): List<Pair<Reminder, Long>> =
        all().flatMap { r -> Recurrence.occurrences(r, from, to).map { r to it } }.sortedBy { it.second }

    private fun query(selection: String?, args: Array<String>?): List<Reminder> =
        db.readableDatabase.query(TABLE, null, selection, args, null, null, "time ASC").use { c ->
            val list = mutableListOf<Reminder>()
            while (c.moveToNext()) {
                list += Reminder(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    title = c.getString(c.getColumnIndexOrThrow("title")),
                    timeMillis = c.getLong(c.getColumnIndexOrThrow("time")),
                    repeat = Repeat.from(c.getString(c.getColumnIndexOrThrow("repeat"))),
                    done = c.getInt(c.getColumnIndexOrThrow("done")) != 0,
                    alarm = c.getInt(c.getColumnIndexOrThrow("alarm")) != 0,
                    leadMinutes = c.getInt(c.getColumnIndexOrThrow("lead")),
                    eventId = c.getColumnIndexOrThrow("event_id").let { i -> if (c.isNull(i)) null else c.getLong(i) },
                )
            }
            list
        }

    private fun values(r: Reminder) = ContentValues().apply {
        put("id", r.id)
        put("title", r.title)
        put("time", r.timeMillis)
        put("repeat", r.repeat.key)
        put("done", if (r.done) 1 else 0)
        put("alarm", if (r.alarm) 1 else 0)
        put("lead", r.leadMinutes)
        if (r.eventId != null) put("event_id", r.eventId) else putNull("event_id")
    }

    private companion object {
        const val TABLE = "reminders"
        const val OLD_KEY = "reminders"
    }
}

/**
 * Lê o bloco de lembrete que a IA escreve no fim da resposta, no formato
 * `[[LEMBRETE {"titulo": "...", "quando": "2026-10-07T15:00", "repetir": "semanal"}]]`.
 */
object ReminderParser {

    private val BLOCK = Regex("""\[\[LEMBRETE\s*(\{.*?\})\s*]]""", RegexOption.DOT_MATCHES_ALL)
    private val TOKEN = Regex("""\[\[lembrete:(\d+)]]""")
    private val PT_BR = Locale("pt", "BR")

    data class Request(
        val title: String,
        val timeMillis: Long,
        val repeat: Repeat = Repeat.NONE,
        val leadMinutes: Int = 0,
    )

    /** Devolve o texto sem o bloco e o primeiro pedido de lembrete válido. */
    fun extract(text: String, timeZone: TimeZone = TimeZone.getDefault()): Pair<String, Request?> =
        extractAll(text, timeZone).let { (clean, list) -> clean to list.firstOrNull() }

    /**
     * Devolve o texto sem os blocos e todos os pedidos de lembrete válidos
     * (uma foto pode ter vários compromissos).
     */
    fun extractAll(text: String, timeZone: TimeZone = TimeZone.getDefault()): Pair<String, List<Request>> {
        val matches = BLOCK.findAll(text).toList()
        if (matches.isEmpty()) return text to emptyList()
        var clean = text
        matches.asReversed().forEach { clean = clean.removeRange(it.range) }
        clean = clean.replace(Regex("\n{3,}"), "\n\n").trimEnd()
        val requests = matches.mapNotNull { m -> parseRequest(m.groupValues[1], timeZone) }
        return clean to requests
    }

    private fun parseRequest(raw: String, timeZone: TimeZone): Request? = runCatching {
        val json = JSONObject(raw)
        val title = json.getString("titulo").trim()
        val time = parseTime(json.getString("quando"), timeZone)
        val repeat = Repeat.from(json.optString("repetir"))
        val lead = json.optInt("antes", 0).coerceIn(0, MAX_LEAD_MINUTES)
        if (title.isEmpty() || time == null) null else Request(title, time, repeat, lead)
    }.getOrNull()

    /** Marca no texto salvo qual lembrete foi criado, para a tela mostrar o cartão. */
    fun token(id: Long) = "[[lembrete:$id]]"

    fun tokenIds(text: String): List<Long> = TOKEN.findAll(text).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()

    fun stripTokens(text: String) = text.replace(TOKEN, "").trimEnd()

    /** Esconde os blocos (prontos ou ainda incompletos) enquanto a resposta chega em streaming. */
    fun hidePartial(raw: String): String {
        val text = raw.replace(BLOCK, "").trimEnd()
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

    /**
     * Instrução extra enviada ao modelo para ele saber criar lembretes e responder sobre a agenda.
     * [agenda] são os próximos lembretes do usuário.
     */
    fun instructions(
        now: Date = Date(),
        timeZone: TimeZone = TimeZone.getDefault(),
        agenda: List<Reminder> = emptyList(),
    ): String {
        val format = SimpleDateFormat("EEEE, yyyy-MM-dd'T'HH:mm", PT_BR).apply { this.timeZone = timeZone }
        val upcoming = if (agenda.isEmpty()) "A agenda do usuário está vazia." else
            "Próximos lembretes do usuário (use para responder perguntas sobre a agenda dele):\n" +
                agenda.take(20).joinToString("\n") { r ->
                    "- ${format.format(Date(r.timeMillis))}: ${r.title}" +
                        if (r.repeat != Repeat.NONE) " (${r.repeat.label.lowercase(PT_BR)})" else ""
                }
        return """
            Agora é ${format.format(now)} (fuso ${timeZone.id}).
            Você cria compromissos no aparelho do usuário. Cada compromisso criado toca o alarme do Nero
            no horário e é salvo no calendário do celular automaticamente.
            SEMPRE que ele marcar, agendar, pedir para lembrar ou anotar qualquer coisa com data ou horário
            (por texto, voz ou imagem: convite, consulta, conta, print de conversa, cartaz, receita),
            confirme em uma frase curta e termine a resposta com uma linha por compromisso:
            [[LEMBRETE {"titulo": "texto curto do compromisso", "quando": "AAAA-MM-DDTHH:MM"}]]
            Se a imagem ou o pedido tiver vários compromissos, escreva uma linha para cada um.
            Se ele pedir algo que se repete, acrescente "repetir" com "diario", "semanal" ou "mensal", por exemplo:
            [[LEMBRETE {"titulo": "Tomar remédio", "quando": "2026-10-08T08:00", "repetir": "diario"}]]
            Se ele pedir para ser avisado antes ("me avisa 1 hora antes", "um dia antes"), acrescente "antes"
            com os minutos de antecedência: o alarme toca nesse momento. Exemplo, consulta às 15h com aviso
            1 hora antes:
            [[LEMBRETE {"titulo": "Consulta", "quando": "2026-10-08T15:00", "antes": 60}]]
            Se ele disser o horário do aviso ("me avisa às 14h" para algo às 15h), calcule a diferença em
            minutos e use em "antes". O máximo é 10080 (uma semana). Sem pedido de aviso antes, não use "antes".
            Em "quando" vai sempre o horário do compromisso, nunca o do aviso.
            Use horário local de 24h. Se faltar o horário, use 09:00. Se faltar a data, pergunte antes
            e não escreva a linha. Não peça confirmação quando data e hora estiverem claras.
            Nunca escreva essa linha em outras situações.
            Se o novo lembrete cair no mesmo horário de outro já existente, avise o usuário.
            $upcoming
        """.trimIndent()
    }

    /** Formato amigável para mostrar a data do lembrete. */
    fun describe(timeMillis: Long, now: Long = System.currentTimeMillis()): String {
        val target = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        val today = Calendar.getInstance().apply { this.timeInMillis = now }
        val hour = SimpleDateFormat("HH:mm", PT_BR).format(Date(timeMillis))
        val day = when (daysBetween(today, target)) {
            -1 -> "Ontem"
            0 -> "Hoje"
            1 -> "Amanhã"
            else -> SimpleDateFormat("EEE, d 'de' MMM", PT_BR).format(Date(timeMillis))
                .replaceFirstChar { it.uppercase() }
        }
        return "$day às $hour"
    }

    /** Data e repetição, como "Amanhã às 08:00 · Todo dia". */
    fun describe(reminder: Reminder, now: Long = System.currentTimeMillis()): String =
        describe(reminder.timeMillis, now) +
            (if (reminder.repeat != Repeat.NONE) " · ${reminder.repeat.label}" else "") +
            (if (reminder.leadMinutes > 0) " · Avisa ${leadLabel(reminder.leadMinutes).lowercase(PT_BR)}" else "")

    private fun daysBetween(a: Calendar, b: Calendar): Int {
        fun dayStart(c: Calendar) = (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return Math.round((dayStart(b) - dayStart(a)) / 86_400_000.0).toInt()
    }
}
