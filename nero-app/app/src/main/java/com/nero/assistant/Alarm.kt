package com.nero.assistant

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nero.assistant.data.Reminder
import com.nero.assistant.data.ReminderStore
import com.nero.assistant.data.Repeat
import com.nero.assistant.data.leadLabel
import com.nero.assistant.ui.AuroraBackground
import com.nero.assistant.ui.N
import com.nero.assistant.ui.NeroFonts
import com.nero.assistant.ui.Shapes
import com.nero.assistant.ui.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * O alarme do Nero: toca o som de alarme (subindo o volume aos poucos), vibra, acende a tela com
 * [AlarmActivity] e mostra uma notificação com "Concluído" e "Adiar". Para sozinho depois de
 * [RING_MINUTES] minutos e deixa uma notificação de alarme não atendido.
 */
class AlarmService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var current: Ringing? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> ring(
                Ringing(
                    id = intent.getLongExtra(EXTRA_ID, -1L),
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    eventTime = intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis()),
                    leadMinutes = intent.getIntExtra(EXTRA_LEAD, 0),
                    snoozed = intent.getBooleanExtra(EXTRA_SNOOZED, false),
                    test = intent.getBooleanExtra(EXTRA_TEST, false),
                )
            )
            ACTION_DONE -> finish(Outcome.DONE)
            ACTION_SNOOZE -> finish(Outcome.SNOOZE)
            else -> if (current == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ring(ringing: Ringing) {
        // Um alarme novo enquanto outro toca: o anterior vira notificação de não atendido.
        current?.let { previous -> if (previous.id != ringing.id) missed(previous) }
        current = ringing
        ensureChannel(this)
        val notification = buildNotification(ringing)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nero:alarme")
            .apply { acquire(RING_MINUTES * 60_000L + 5_000) }
        startSound()
        startVibration()
        // Abre a tela do alarme direto quando o Android deixa (tela apagada usa a notificação em tela cheia).
        runCatching { startActivity(activityIntent(this, ringing)) }
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ finish(Outcome.MISSED) }, RING_MINUTES * 60_000L)
        rampVolume(0.15f)
        listener?.invoke(ringing)
    }

    private fun finish(outcome: Outcome) {
        val ringing = current
        current = null
        stopAlarm()
        if (ringing != null && !ringing.test) {
            val store = ReminderStore(this)
            when (outcome) {
                Outcome.DONE -> store.get(ringing.id)?.let { r ->
                    // Os que se repetem já foram para a próxima vez quando o alarme tocou.
                    if (r.repeat == Repeat.NONE && r.timeMillis <= ringing.eventTime) store.update(r.copy(done = true))
                }
                Outcome.SNOOZE -> ReminderAlarms.snooze(this, ringing.id)
                Outcome.MISSED -> missed(ringing)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        listener?.invoke(null)
    }

    private fun missed(ringing: Ringing) {
        if (ringing.test) return
        val reminder = ReminderStore(this).get(ringing.id) ?: return
        ReminderAlarms.notify(this, reminder.copy(title = ringing.title, timeMillis = ringing.eventTime), missed = true)
    }

    private fun startSound() {
        player?.release()
        val chosen = ReminderStore(this).alarmSound.takeIf { it.isNotEmpty() }?.let(Uri::parse)
        val candidates = listOfNotNull(
            chosen,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            val p = MediaPlayer()
            val ok = runCatching {
                p.setAudioAttributes(ALARM_AUDIO)
                p.setDataSource(this, uri)
                p.isLooping = true
                p.prepare()
                p.setVolume(0.15f, 0.15f)
                p.start()
            }.isSuccess
            if (ok) {
                player = p
                return
            }
            p.release()
        }
    }

    /** Começa baixo e chega ao volume máximo em uns 20 segundos, como um despertador. */
    private fun rampVolume(level: Float) {
        val p = player ?: return
        runCatching { p.setVolume(level, level) }
        if (level < 1f) main.postDelayed({ if (player === p) rampVolume(minOf(1f, level + 0.085f)) }, 2_000)
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        vibrator = v
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 700), 0), ALARM_AUDIO)
    }

    private fun stopAlarm() {
        main.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopAlarm()
        if (current != null) listener?.invoke(null)
        current = null
        super.onDestroy()
    }

    private fun buildNotification(r: Ringing): Notification {
        val open = PendingIntent.getActivity(
            this, 0, activityIntent(this, r),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(r.title)
            .setContentText(subtitle(r))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setColor(N.LILAC_DEEP)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(serviceAction(ACTION_DONE, R.drawable.ic_check, "Concluído", 1))
            .addAction(serviceAction(ACTION_SNOOZE, R.drawable.ic_alarm, "Adiar ${ReminderAlarms.SNOOZE_MINUTES} min", 2))
            .build()
    }

    private fun serviceAction(action: String, icon: Int, title: String, code: Int) = Notification.Action.Builder(
        android.graphics.drawable.Icon.createWithResource(this, icon), title,
        PendingIntent.getService(this, code, Intent(this, AlarmService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
    ).build()

    /** O que está tocando agora. */
    data class Ringing(
        val id: Long,
        val title: String,
        val eventTime: Long,
        val leadMinutes: Int,
        val snoozed: Boolean,
        val test: Boolean,
    )

    private enum class Outcome { DONE, SNOOZE, MISSED }

    companion object {
        const val ALARM_CHANNEL_ID = "nero_alarme"
        private const val NOTIFICATION_ID = 7_007
        private const val RING_MINUTES = 3
        private const val ACTION_RING = "com.nero.assistant.ALARME_TOCAR"
        private const val ACTION_DONE = "com.nero.assistant.ALARME_FEITO"
        private const val ACTION_SNOOZE = "com.nero.assistant.ALARME_ADIAR"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "titulo"
        private const val EXTRA_TIME = "hora"
        private const val EXTRA_LEAD = "antes"
        private const val EXTRA_SNOOZED = "adiado"
        private const val EXTRA_TEST = "teste"

        private val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** A tela do alarme acompanha o serviço: recebe o alarme atual ou null quando ele para. */
        internal var listener: ((Ringing?) -> Unit)? = null

        fun start(context: Context, reminder: Reminder, snoozed: Boolean = false) {
            context.startForegroundService(
                Intent(context, AlarmService::class.java).setAction(ACTION_RING)
                    .putExtra(EXTRA_ID, reminder.id)
                    .putExtra(EXTRA_TITLE, reminder.title)
                    .putExtra(EXTRA_TIME, reminder.timeMillis)
                    .putExtra(EXTRA_LEAD, reminder.leadMinutes)
                    .putExtra(EXTRA_SNOOZED, snoozed)
            )
        }

        /** Toca o alarme na hora, só para ouvir e ver como fica. */
        fun test(context: Context) {
            context.startForegroundService(
                Intent(context, AlarmService::class.java).setAction(ACTION_RING)
                    .putExtra(EXTRA_ID, -1L)
                    .putExtra(EXTRA_TITLE, "Teste do alarme do Nero")
                    .putExtra(EXTRA_TIME, System.currentTimeMillis())
                    .putExtra(EXTRA_TEST, true)
            )
        }

        fun done(context: Context) = context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_DONE))
        fun snooze(context: Context) = context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_SNOOZE))

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(ALARM_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(ALARM_CHANNEL_ID, "Alarme", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Alarme do Nero na hora dos compromissos"
                        // O som e a vibração vêm do próprio alarme, não da notificação.
                        setSound(null, null)
                        enableVibration(false)
                        setBypassDnd(true)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                )
            }
        }

        fun subtitle(r: Ringing): String {
            val time = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(r.eventTime))
            return when {
                r.test -> "Assim o Nero vai te chamar"
                r.snoozed -> "Lembrete adiado"
                r.eventTime - System.currentTimeMillis() > 60_000 -> "Às $time · ${leadLabel(r.leadMinutes).replace(" antes", "")} para começar"
                else -> "Agora · $time"
            }
        }

        private fun activityIntent(context: Context, r: Ringing) = Intent(context, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .putExtra(EXTRA_TITLE, r.title)
            .putExtra(EXTRA_TIME, r.eventTime)
            .putExtra(EXTRA_LEAD, r.leadMinutes)
            .putExtra(EXTRA_SNOOZED, r.snoozed)
            .putExtra(EXTRA_TEST, r.test)

        internal fun ringingFrom(intent: Intent) = Ringing(
            id = -1,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            eventTime = intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis()),
            leadMinutes = intent.getIntExtra(EXTRA_LEAD, 0),
            snoozed = intent.getBooleanExtra(EXTRA_SNOOZED, false),
            test = intent.getBooleanExtra(EXTRA_TEST, false),
        )
    }
}

/** Tela cheia do alarme, por cima da tela de bloqueio: o gato, o horário, o compromisso e dois botões. */
class AlarmActivity : Activity() {

    private lateinit var fonts: NeroFonts
    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = N.OLIVE
        window.navigationBarColor = N.OLIVE_DEEP
        fonts = NeroFonts(this)
        root = FrameLayout(this)
        setContentView(root)
        show(AlarmService.ringingFrom(intent))
        AlarmService.listener = { ringing -> if (ringing == null) finish() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(AlarmService.ringingFrom(intent))
    }

    override fun onDestroy() {
        AlarmService.listener = null
        super.onDestroy()
    }

    private fun show(r: AlarmService.Ringing) {
        root.removeAllViews()
        root.addView(AuroraBackground(this), FrameLayout.LayoutParams(MATCH, MATCH))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(56), dp(24), dp(32))
        }
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        // O gato pulsando dentro de um anel em degradê.
        val ring = FrameLayout(this).apply {
            background = Shapes.auroraCircle()
            setPadding(dp(5), dp(5), dp(5), dp(5))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.nero_logo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Shapes.circle(N.OLIVE_DEEP)
                clipToOutline = true
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        column.addView(ring, LinearLayout.LayoutParams(dp(120), dp(120)))
        pulse(ring)

        val label = when {
            r.test -> "TESTE"
            r.snoozed -> "ADIADO"
            r.eventTime - System.currentTimeMillis() > 60_000 -> "EM " + leadLabel(r.leadMinutes).replace(" antes", "").uppercase(PT_BR)
            else -> "AGORA"
        }
        column.addView(text(label, 16f, N.LILAC, fonts.dots).apply {
            letterSpacing = 0.1f
            setPadding(0, dp(28), 0, 0)
        })
        column.addView(text(SimpleDateFormat("HH:mm", PT_BR).format(Date(r.eventTime)), 84f, N.TEXT, fonts.dots).apply {
            setPadding(0, dp(4), 0, 0)
        })
        column.addView(text(r.title, 28f, N.TEXT, fonts.light).apply {
            setPadding(0, dp(8), 0, dp(6))
            setLineSpacing(0f, 1.1f)
        })
        column.addView(text(
            SimpleDateFormat("EEEE, d 'de' MMMM", PT_BR).format(Date(r.eventTime)).replaceFirstChar { it.uppercase() },
            15f, N.MUTED, fonts.regular,
        ))

        column.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        column.addView(text("Concluído", 18f, N.INK, fonts.semibold).apply {
            background = Shapes.solid(context, 32, N.CREAM)
            setOnClickListener {
                AlarmService.done(this@AlarmActivity)
                finish()
            }
        }, LinearLayout.LayoutParams(MATCH, dp(64)))
        column.addView(text("Adiar ${ReminderAlarms.SNOOZE_MINUTES} min", 17f, N.TEXT, fonts.regular).apply {
            background = Shapes.glass(context, 32)
            setOnClickListener {
                AlarmService.snooze(this@AlarmActivity)
                finish()
            }
        }, LinearLayout.LayoutParams(MATCH, dp(60)).apply { topMargin = dp(12) })
    }

    private fun pulse(view: View) {
        view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(700)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(700).withEndAction {
                    if (!isFinishing) pulse(view)
                }.start()
            }.start()
    }

    private fun text(value: String, size: Float, color: Int, face: android.graphics.Typeface) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = face
        gravity = Gravity.CENTER
    }

    private companion object {
        val PT_BR = Locale("pt", "BR")
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
