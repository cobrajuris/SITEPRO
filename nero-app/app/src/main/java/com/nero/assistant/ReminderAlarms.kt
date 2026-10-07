package com.nero.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.nero.assistant.data.Recurrence
import com.nero.assistant.data.Reminder
import com.nero.assistant.data.ReminderStore
import com.nero.assistant.data.Repeat

/** Agenda e cancela os alarmes dos lembretes, e reúne as ações que mexem neles. */
object ReminderAlarms {

    const val CHANNEL_ID = "nero_lembretes"
    private const val EXTRA_ID = "reminder_id"
    private const val EXTRA_SNOOZE = "snooze"
    private const val ACTION_DONE = "com.nero.assistant.LEMBRETE_FEITO"
    private const val ACTION_SNOOZE = "com.nero.assistant.LEMBRETE_ADIAR"
    const val SNOOZE_MINUTES = 10
    private const val SNOOZE_SALT = 0x5A5A5A5AL

    fun schedule(context: Context, reminder: Reminder) {
        val now = System.currentTimeMillis()
        if (reminder.done || reminder.timeMillis <= now) return
        // Com antecedência que já passou (ex.: criado 5 min antes com aviso de 15), toca no horário.
        val at = if (reminder.alertAt > now) reminder.alertAt else reminder.timeMillis
        setAlarm(context, at, alarmIntent(context, reminder.id, snooze = false))
    }

    fun cancel(context: Context, id: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(alarmIntent(context, id, snooze = false))
        alarms.cancel(alarmIntent(context, id, snooze = true))
        context.getSystemService(NotificationManager::class.java).cancel(id.toInt())
    }

    fun rescheduleAll(context: Context) {
        val store = ReminderStore(context)
        val now = System.currentTimeMillis()
        store.all().filterNot { it.done }.forEach { r ->
            // Lembretes que se repetem e passaram com o celular desligado pulam para a próxima vez.
            val current = if (r.repeat != Repeat.NONE && r.timeMillis <= now) {
                r.copy(timeMillis = Recurrence.next(r.timeMillis, r.repeat, now)!!).also(store::update)
            } else r
            schedule(context, current)
        }
    }

    /** Salva (cria ou edita) um lembrete e acerta o alarme. */
    fun save(context: Context, store: ReminderStore, reminder: Reminder) {
        store.update(reminder)
        cancel(context, reminder.id)
        schedule(context, reminder)
    }

    /** Conclui o lembrete; os que se repetem passam para a próxima vez. */
    fun complete(context: Context, store: ReminderStore, id: Long): Reminder? {
        cancel(context, id)
        return store.complete(id)?.also { schedule(context, it) }
    }

    fun delete(context: Context, store: ReminderStore, id: Long) {
        cancel(context, id)
        store.remove(id)
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lembretes", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisos dos lembretes criados pelo Nero"
                    enableVibration(true)
                }
            )
        }
    }

    /** Toca de novo daqui a [SNOOZE_MINUTES] minutos. */
    fun snooze(context: Context, id: Long) =
        setAlarm(context, System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L, alarmIntent(context, id, snooze = true))

    /** Chegou a hora: toca o alarme (ou só avisa) e, se o lembrete se repete, já agenda a próxima vez. */
    internal fun fire(context: Context, id: Long, snooze: Boolean) {
        val store = ReminderStore(context)
        val reminder = store.get(id) ?: return
        if (reminder.done) return
        // Adiado: mostra a hora de agora (nos que se repetem, o horário salvo já é o da próxima vez).
        val shown = if (snooze) reminder.copy(timeMillis = System.currentTimeMillis(), leadMinutes = 0) else reminder
        val rang = reminder.alarm && runCatching { AlarmService.start(context, shown, snooze) }.isSuccess
        if (!rang) notify(context, reminder)
        if (!snooze && reminder.repeat != Repeat.NONE) {
            val next = Recurrence.next(reminder.timeMillis, reminder.repeat, System.currentTimeMillis()) ?: return
            val updated = reminder.copy(timeMillis = next)
            store.update(updated)
            schedule(context, updated)
        }
    }

    internal fun handleAction(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L).takeIf { it >= 0 } ?: return
        val store = ReminderStore(context)
        when (intent.action) {
            ACTION_DONE -> {
                val reminder = store.get(id) ?: return
                // Se repete, o alarme já foi para a próxima vez quando tocou: só fecha o aviso.
                if (reminder.repeat == Repeat.NONE) store.update(reminder.copy(done = true))
                context.getSystemService(NotificationManager::class.java).cancel(id.toInt())
            }
            ACTION_SNOOZE -> {
                context.getSystemService(NotificationManager::class.java).cancel(id.toInt())
                snooze(context, id)
            }
        }
    }

    /** Notificação comum do lembrete (também usada quando o alarme toca e ninguém atende). */
    internal fun notify(context: Context, reminder: Reminder, missed: Boolean = false) {
        ensureChannel(context)
        val id = reminder.id
        val open = PendingIntent.getActivity(
            context, id.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_AGENDA, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(reminder.timeMillis))
        val text = when {
            missed -> "Alarme não atendido · $time"
            reminder.timeMillis - System.currentTimeMillis() > 60_000 -> "Às $time"
            else -> "Está na hora."
        } + if (reminder.repeat != Repeat.NONE) " · ${reminder.repeat.label}" else ""
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nero lembra: ${reminder.title}")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(action(context, id, ACTION_DONE, R.drawable.ic_check, "Concluído"))
            .addAction(action(context, id, ACTION_SNOOZE, R.drawable.ic_alarm, "Adiar $SNOOZE_MINUTES min"))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id.toInt(), notification) }
    }

    private fun action(context: Context, id: Long, action: String, icon: Int, title: String): Notification.Action {
        val pending = PendingIntent.getBroadcast(
            context, (id.toInt() * 31) + action.hashCode(),
            Intent(context, ReminderActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, icon), title, pending).build()
    }

    private fun setAlarm(context: Context, at: Long, pending: PendingIntent) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        // Alarme exato quando o Android permite; senão, um alarme que pode atrasar alguns minutos.
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exactAllowed) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun alarmIntent(context: Context, id: Long, snooze: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, if (snooze) (id xor SNOOZE_SALT).toInt() else id.toInt(),
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_SNOOZE, snooze),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun idFrom(intent: Intent) = intent.getLongExtra(EXTRA_ID, -1L)
    internal fun isSnooze(intent: Intent) = intent.getBooleanExtra(EXTRA_SNOOZE, false)
}

/** Dispara a notificação quando chega a hora do lembrete. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = ReminderAlarms.idFrom(intent)
        if (id >= 0) ReminderAlarms.fire(context, id, ReminderAlarms.isSnooze(intent))
    }
}

/** Botões da notificação: "Concluído" e "Adiar". */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ReminderAlarms.handleAction(context, intent)
}

/** Depois de reiniciar o celular, o Android apaga os alarmes: agenda tudo de novo. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ReminderAlarms.rescheduleAll(context)
        }
    }
}
