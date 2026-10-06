package com.enzo.ilhadinamica

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.RingtoneManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Calendar
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Serviço em primeiro plano que desenha a ilha (bolha + cápsula) por cima de todos os apps,
 * centrada no furo da câmera frontal.
 */
class IslandService : Service() {

    companion object {
        const val ACTION_STOP = "com.enzo.ilhadinamica.STOP"
        const val ACTION_CMD = "com.enzo.ilhadinamica.CMD"
        const val EXTRA_JSON = "json"
        private const val CHANNEL = "ilha"
        private const val NOTIF_ID = 26

        @Volatile
        var instance: IslandService? = null
            private set

        /** Último estado informado pela ilha: {"tab":"music","open":false}. */
        @Volatile
        var lastState: String = "{\"tab\":\"music\",\"open\":false}"

        fun start(ctx: Context) {
            val i = Intent(ctx, IslandService::class.java)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, IslandService::class.java).setAction(ACTION_STOP))
        }

        /** Envia um comando JSON para a ilha (se estiver rodando). */
        fun send(json: JSONObject) {
            instance?.js(json)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: IslandPrefs
    private var root: FrameLayout? = null
    private var web: WebView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var pageReady = false
    private val pending = ArrayList<String>()

    private var camX = 0f
    private var camY = 0f
    private var cutoutDp = 20f
    private var landscape = false

    private var controller: MediaController? = null
    private var lastArtKey: String? = null
    private var ringtone: android.media.Ringtone? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = IslandPrefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        if (Settings.canDrawOverlays(this)) createOverlay() else stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.enabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CMD -> intent.getStringExtra(EXTRA_JSON)?.let { js(JSONObject(it)) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        stopRing()
        controller = null
        root?.let { runCatching { wm.removeView(it) } }
        web?.destroy()
        root = null
        web = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notificação fixa

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, IslandService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------------------------------------------------------- janela flutuante

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createOverlay() {
        val w = WebView(this)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.textZoom = 100
        w.addJavascriptInterface(Host(), "IslandHost")
        w.webViewClient = object : WebViewClient() {}

        val frame = object : FrameLayout(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    js(JSONObject().put("type", "close"))
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }
        }
        frame.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            dp(66f), dp(62f), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        params.title = "Ilha dinâmica"

        root = frame
        web = w
        lp = params
        locateCamera()
        params.x = (camX - params.width / 2f).roundToInt()
        params.y = 0
        wm.addView(frame, params)
        w.loadUrl("file:///android_asset/island.html")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({
            locateCamera()
            pushConfig()
            lp?.let { applySize(it.width, it.height) }
        }, 300)
    }

    /** Descobre o centro do furo da câmera (display cutout); sem furo, usa o topo central. */
    private fun locateCamera() {
        val dm = resources.displayMetrics
        var screenW = dm.widthPixels.toFloat()
        var cutout: android.view.DisplayCutout? = null
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            screenW = m.bounds.width().toFloat()
            cutout = m.windowInsets.displayCutout
        } else if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            cutout = wm.defaultDisplay.cutout
        }
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        var rect: Rect? = null
        if (cutout != null) {
            rect = cutout.boundingRects.firstOrNull { it.top <= 0 && it.width() < screenW * 0.6f }
        }
        if (rect != null && !rect.isEmpty) {
            camX = rect.exactCenterX()
            camY = rect.exactCenterY()
            cutoutDp = min(rect.width(), rect.height()) / dm.density
        } else {
            camX = screenW / 2f
            camY = statusBarHeight() / 2f
            cutoutDp = 20f
        }
        camX += prefs.offsetX * dm.density
        camY += prefs.offsetY * dm.density
    }

    private fun statusBarHeight(): Int {
        @SuppressLint("InternalInsetResource", "DiscouragedApi")
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24f)
    }

    private fun pushConfig() {
        val dm = resources.displayMetrics
        val screenWdp = (if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width() else dm.widthPixels) / dm.density
        // A bolha do protótipo tem 34 dp em volta de uma câmera de 20 dp.
        val auto = (cutoutDp / 20f).coerceIn(0.85f, 1.6f)
        js(
            JSONObject()
                .put("type", "cfg")
                .put("cy", camY / dm.density)
                .put("screenW", screenWdp)
                .put("scale", prefs.scale.toDouble())
                .put("bscale", (auto * prefs.bubbleScale).toDouble())
        )
    }

    /** Reaplica posição/tamanho após o usuário mexer nos ajustes. */
    fun reconfigure() {
        main.post {
            locateCamera()
            pushConfig()
            lp?.let { applySize(it.width, it.height) }
        }
    }

    private fun applySize(wPx: Int, hPx: Int) {
        val p = lp ?: return
        val r = root ?: return
        p.width = wPx
        p.height = hPx
        p.x = (camX - wPx / 2f).roundToInt()
        p.y = 0
        r.visibility = if (landscape) View.GONE else View.VISIBLE
        runCatching { wm.updateViewLayout(r, p) }
    }

    fun js(cmd: JSONObject) {
        val s = cmd.toString()
        main.post {
            val w = web
            if (w == null || !pageReady) {
                pending.add(s)
                return@post
            }
            w.evaluateJavascript("window.island&&island.cmd($s)", null)
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    // ---------------------------------------------------------------- ponte JS → Android

    inner class Host {
        @JavascriptInterface
        fun ready() {
            main.post {
                pageReady = true
                pushConfig()
                val copy = ArrayList(pending)
                pending.clear()
                copy.forEach { web?.evaluateJavascript("window.island&&island.cmd($it)", null) }
                refreshMedia()
                pushBrightness()
                pushCalendar()
            }
        }

        @JavascriptInterface
        fun resize(w: Float, h: Float) {
            main.post { applySize(dp(w), dp(h)) }
        }

        @JavascriptInterface
        fun report(json: String) {
            lastState = json
        }

        @JavascriptInterface
        fun media(action: String) {
            main.post { mediaAction(action) }
        }

        @JavascriptInterface
        fun setVolume(pct: Int) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, (pct * max / 100f).roundToInt(), 0) }
        }

        @JavascriptInterface
        fun setBrightness(pct: Int, mode: Int) {
            if (!Settings.System.canWrite(this@IslandService)) return
            val cr = contentResolver
            runCatching {
                Settings.System.putInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (mode == 0) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                if (mode != 0) {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (pct.coerceIn(1, 100) * 255 / 100f).roundToInt())
                }
            }
        }

        @JavascriptInterface
        fun buzz(kind: String) {
            main.post { feedback(kind) }
        }

        @JavascriptInterface
        fun openApp(tab: String) {
            main.post { launchFor(tab) }
        }
    }

    // ---------------------------------------------------------------- feedback

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(VIBRATOR_SERVICE) as Vibrator
    }

    private fun feedback(kind: String) {
        val v = vibrator()
        when (kind) {
            "stop" -> stopRing()
            "done" -> vibrate(v, longArrayOf(0, 30, 60, 30))
            "timer" -> {
                vibrate(v, longArrayOf(0, 400, 200, 400, 200, 400))
                playTone(RingtoneManager.TYPE_ALARM, 4000)
            }
            "ring" -> {
                vibrate(v, longArrayOf(0, 250, 150, 250))
                if (ringtone?.isPlaying != true) playTone(RingtoneManager.TYPE_RINGTONE, 20000)
            }
        }
    }

    private fun vibrate(v: Vibrator?, pattern: LongArray) {
        if (v == null || !v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun playTone(type: Int, maxMs: Long) {
        runCatching {
            stopRing()
            val r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(type)) ?: return
            ringtone = r
            r.play()
            main.postDelayed({ if (ringtone === r) stopRing() }, maxMs)
        }
    }

    private fun stopRing() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    private fun launchFor(tab: String) {
        val pm = packageManager
        val intent: Intent? = when (tab) {
            "cal" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
            "maps" -> pm.getLaunchIntentForPackage("com.google.android.apps.maps")
                ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)
            "ifood" -> pm.getLaunchIntentForPackage("br.com.brainweb.ifood")
            else -> NotifListener.packagesFor(tab).firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        }
        intent ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
        js(JSONObject().put("type", "close"))
    }

    // ---------------------------------------------------------------- mídia real

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = pushMedia(false)
        override fun onMetadataChanged(metadata: MediaMetadata?) = pushMedia(prefs.realEvents)
        override fun onSessionDestroyed() = refreshMedia()
    }

    /** Escolhe a sessão de mídia ativa (precisa do acesso às notificações). */
    fun refreshMedia() {
        main.post {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = runCatching {
                msm.getActiveSessions(ComponentName(this, NotifListener::class.java))
            }.getOrNull() ?: return@post
            val pick = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
            if (pick?.sessionToken != controller?.sessionToken) {
                controller?.unregisterCallback(mediaCallback)
                controller = pick
                pick?.registerCallback(mediaCallback, main)
                lastArtKey = null
            }
            if (pick == null) js(JSONObject().put("type", "media").put("src", "none"))
            else pushMedia(false)
        }
    }

    private fun pushMedia(show: Boolean) {
        val c = controller ?: return
        val md = c.metadata
        val st = c.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING
        var posMs = st?.position ?: 0L
        if (playing && st != null && st.lastPositionUpdateTime > 0) {
            posMs += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val isYt = c.packageName == "com.google.android.youtube" || c.packageName == "app.revanced.android.youtube"
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val o = JSONObject()
            .put("type", "media")
            .put("src", if (isYt) "yt" else "music")
            .put("title", title)
            .put("artist", artist)
            .put("playing", playing)
            .put("pos", posMs / 1000)
            .put("dur", dur / 1000)
            .put("vol", vol)
            .put("show", show && playing)
        val artKey = c.packageName + "|" + title
        if (!isYt && artKey != lastArtKey) {
            lastArtKey = artKey
            val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            o.put("art", bmp?.let { toDataUrl(it) } ?: "")
        }
        js(o)
    }

    private fun toDataUrl(src: Bitmap): String? = runCatching {
        val size = 160
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private fun mediaAction(action: String) {
        val c = controller
        val tc = c?.transportControls
        when {
            action == "hangup" -> {}
            tc == null -> {}
            action == "play" -> tc.play()
            action == "pause" -> tc.pause()
            action == "next" -> tc.skipToNext()
            action == "prev" -> tc.skipToPrevious()
            action.startsWith("seek:") -> action.removePrefix("seek:").toLongOrNull()?.let { tc.seekTo(it) }
        }
    }

    // ---------------------------------------------------------------- brilho e calendário

    private fun pushBrightness() {
        runCatching {
            val cr = contentResolver
            val v = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
            val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            js(JSONObject().put("type", "brightness").put("value", (v * 100 / 255f).roundToInt()).put("auto", auto))
        }
    }

    /** Lê os eventos desta semana (seg–dom) do calendário do aparelho. */
    fun pushCalendar() {
        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        Thread {
            runCatching {
                val cal = Calendar.getInstance()
                val todayIdx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.add(Calendar.DAY_OF_MONTH, -todayIdx)
                val start = cal.timeInMillis
                val end = start + 7L * 24 * 3600 * 1000
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                    android.content.ContentUris.appendId(it, start)
                    android.content.ContentUris.appendId(it, end)
                }.build()
                val days = Array(7) { ArrayList<String>() }
                var next: Long = Long.MAX_VALUE
                val now = System.currentTimeMillis()
                contentResolver.query(
                    uri,
                    arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.ALL_DAY),
                    null, null, CalendarContract.Instances.BEGIN
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val begin = cur.getLong(0)
                        val color = cur.getInt(1)
                        val idx = ((begin - start) / (24L * 3600 * 1000)).toInt()
                        if (idx in 0..6) days[idx].add(String.format("#%06X", 0xFFFFFF and color))
                        if (begin > now && cur.getInt(2) == 0 && idx == todayIdx && begin < next) next = begin
                    }
                }
                val o = JSONObject().put("type", "calendar").put("days", JSONArray(days.map { JSONArray(it) }))
                if (next != Long.MAX_VALUE) {
                    val c2 = Calendar.getInstance().apply { timeInMillis = next }
                    o.put("next", String.format("%02d:%02d", c2.get(Calendar.HOUR_OF_DAY), c2.get(Calendar.MINUTE)))
                }
                js(o)
            }
        }.start()
    }
}
