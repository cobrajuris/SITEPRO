package com.nero.assistant

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.nero.assistant.data.Reminder
import com.nero.assistant.data.ReminderStore
import com.nero.assistant.data.Repeat
import java.util.TimeZone

/**
 * Salva os compromissos do Nero direto no calendário do celular (normalmente o Google Agenda da
 * conta principal), sem abrir outro app. O aviso fica por conta do alarme do Nero, então o evento
 * é criado sem lembrete próprio, para não tocar duas vezes.
 */
object CalendarSync {

    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    private const val DURATION_MINUTES = 30

    fun hasPermission(context: Context) = PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun isActive(context: Context, store: ReminderStore) = store.calendarSync && hasPermission(context)

    /** Calendário escolhido: id e nome para mostrar. */
    data class Target(val id: Long, val name: String)

    /** O calendário principal em que dá para escrever, preferindo o da conta Google. */
    fun target(context: Context): Target? {
        if (!hasPermission(context)) return null
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        return runCatching {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
                var best: Target? = null
                var bestScore = -1
                while (c.moveToNext()) {
                    val score = (if (c.getInt(3) == 1) 2 else 0) + (if (c.getString(2) == "com.google") 1 else 0)
                    if (score > bestScore) {
                        bestScore = score
                        best = Target(c.getLong(0), c.getString(1) ?: "Calendário")
                    }
                }
                best
            }
        }.getOrNull()
    }

    /**
     * Cria ou atualiza o evento do lembrete e guarda o id dele. Devolve o lembrete atualizado
     * (igual ao recebido se não deu para salvar).
     */
    fun upsert(context: Context, store: ReminderStore, reminder: Reminder): Reminder {
        if (!isActive(context, store)) return reminder
        return runCatching {
            val values = eventValues(reminder)
            val resolver = context.contentResolver
            val existing = reminder.eventId
            if (existing != null) {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing)
                if (resolver.update(uri, values, null, null) > 0) return reminder
            }
            // Sem evento (ou o usuário apagou o antigo no calendário): cria um novo.
            val calendar = target(context) ?: return reminder
            values.put(CalendarContract.Events.CALENDAR_ID, calendar.id)
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return reminder
            reminder.copy(eventId = ContentUris.parseId(uri)).also(store::update)
        }.getOrDefault(reminder)
    }

    fun delete(context: Context, eventId: Long?) {
        if (eventId == null || !hasPermission(context)) return
        runCatching {
            context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
        }
    }

    /** Depois que a permissão é dada, leva para o calendário os compromissos que ainda não estão lá. */
    fun syncPending(context: Context, store: ReminderStore) {
        if (!isActive(context, store)) return
        store.upcoming().filter { it.eventId == null }.forEach { upsert(context, store, it) }
    }

    private fun eventValues(r: Reminder) = ContentValues().apply {
        put(CalendarContract.Events.TITLE, r.title)
        put(CalendarContract.Events.DESCRIPTION, "Criado pelo Nero")
        put(CalendarContract.Events.DTSTART, r.timeMillis)
        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        put(CalendarContract.Events.HAS_ALARM, 0)
        val rule = when (r.repeat) {
            Repeat.DAILY -> "FREQ=DAILY"
            Repeat.WEEKLY -> "FREQ=WEEKLY"
            Repeat.MONTHLY -> "FREQ=MONTHLY"
            Repeat.NONE -> null
        }
        if (rule != null) {
            // Eventos que se repetem usam duração em vez de horário de fim.
            put(CalendarContract.Events.RRULE, rule)
            put(CalendarContract.Events.DURATION, "PT${DURATION_MINUTES}M")
            putNull(CalendarContract.Events.DTEND)
        } else {
            putNull(CalendarContract.Events.RRULE)
            putNull(CalendarContract.Events.DURATION)
            put(CalendarContract.Events.DTEND, r.timeMillis + DURATION_MINUTES * 60_000L)
        }
    }
}
