package com.nero.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.nero.assistant.data.Reminder
import com.nero.assistant.data.ReminderStore

/** Agenda e cancela os alarmes dos lembretes. */
object ReminderAlarms {

    const val CHANNEL_ID = "nero_lembretes"
    private const val EXTRA_ID = "reminder_id"

    fun schedule(context: Context, reminder: Reminder) {
        if (reminder.timeMillis <= System.currentTimeMillis()) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context, reminder.id)
        // Alarme exato quando o Android permite; senão, um alarme que pode atrasar alguns minutos.
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exactAllowed) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.timeMillis, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.timeMillis, pending)
        }
    }

    fun cancel(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, id))
        context.getSystemService(NotificationManager::class.java).cancel(id.toInt())
    }

    fun rescheduleAll(context: Context) = ReminderStore(context).upcoming().forEach { schedule(context, it) }

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

    internal fun notify(context: Context, id: Long) {
        val reminder = ReminderStore(context).get(id) ?: return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, id.toInt(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nero lembra: ${reminder.title}")
            .setContentText("Está na hora.")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id.toInt(), notification) }
    }

    private fun pendingIntent(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun idFrom(intent: Intent) = intent.getLongExtra(EXTRA_ID, -1L)
}

/** Dispara a notificação quando chega a hora do lembrete. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = ReminderAlarms.idFrom(intent)
        if (id >= 0) ReminderAlarms.notify(context, id)
    }
}

/** Depois de reiniciar o celular, o Android apaga os alarmes: agenda tudo de novo. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ReminderAlarms.rescheduleAll(context)
        }
    }
}
