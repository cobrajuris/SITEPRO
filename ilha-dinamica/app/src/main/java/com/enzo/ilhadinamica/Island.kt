package com.enzo.ilhadinamica

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Ringtone
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
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * O "cérebro" da ilha: bolha nativa em volta da câmera + cápsula (WebView).
 *
 * Pode ser hospedada de dois jeitos:
 *  - pelo serviço de ACESSIBILIDADE (recomendado): as janelas ficam acima da barra de status, então
 *    a bolha recebe o toque mesmo estando na área da câmera, e o sistema mantém o serviço sempre vivo;
 *  - pelo serviço em primeiro plano com "aparecer sobre outros apps" (alternativa).
 *
 * Regras de estabilidade: o renderizador do WebView pode morrer sem derrubar o app; nada vindo do
 * JavaScript ou de outros apps lança exceção para fora; a cápsula fechada é transparente e não-tocável.
 */
class Island(private val svc: Service, private val accessibility: Boolean, private val onFatal: () -> Unit) {

    companion object {
        private const val TAG = "Ilha"
        private const val CAP_W = 340f
        private const val CAP_H = 92f

        /** A ilha ativa no momento (no máximo uma). */
        @Volatile
        var current: Island? = null
            private set

        /** Último estado informado pela ilha: {"tab":"music","open":false}. */
        @Volatile
        var lastState: String = "{\"tab\":\"music\",\"open\":false}"

        /** Envia um comando JSON para a ilha (se estiver rodando). */
        fun send(json: JSONObject) {
            current?.js(json)
        }
    }

    val isAccessibility: Boolean get() = accessibility

    private val main = Handler(Looper.getMainLooper())
    private val prefs = IslandPrefs(svc)
    private lateinit var ui: Context
    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var web: WebView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var bubble: BubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var pageReady = false
    private val pending = ArrayList<String>()
    private var islandOpen = false
    private val rendererDeaths = ArrayList<Long>()

    private var camX = 0f
    private var camY = 0f
    private var cutoutDp = 20f
    private var landscape = false

    private var controller: MediaController? = null
    private var lastArtKey: String? = null
    private var ringtone: Ringtone? = null

    private val overlayType: Int
        get() = if (accessibility) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /** Cria as janelas. Retorna false se não foi possível. */
    fun start(): Boolean {
        current?.takeIf { it !== this }?.stop()
        current = this
        ui = overlayContext()
        wm = ui.getSystemService(WindowManager::class.java)
        return runCatching { createOverlay(); true }.getOrElse {
            Log.e(TAG, "falha ao criar a ilha", it)
            stop()
            false
        }
    }

    fun stop() {
        if (current === this) current = null
        main.removeCallbacksAndMessages(null)
        stopRing()
        runCatching { controller?.unregisterCallback(mediaCallback) }
        controller = null
        if (::wm.isInitialized) destroyOverlay()
    }

    /** Comandos curtos (painel e testes automáticos): "tab:wa", "open", "close", "toggle". */
    fun shortCommand(c: String) {
        val o = JSONObject()
        when {
            c.startsWith("tab:") -> o.put("type", "tab").put("id", c.removePrefix("tab:"))
            c == "open" || c == "close" || c == "toggle" -> o.put("type", c)
            else -> return
        }
        js(o)
    }

    // ---------------------------------------------------------------- janelas flutuantes
    //
    // Duas camadas:
    //  1) bolha — View nativa pequena, fixa em volta da câmera (sempre tocável);
    //  2) cápsula — WebView com tamanho FIXO, posicionada dentro da tela. Fechada, fica
    //     totalmente transparente (alpha 0) e não-tocável, então nunca bloqueia toques nos
    //     apps de baixo (regra do Android 12+) e nunca precisa ser redimensionada (sem tremidas).

    private fun overlayContext(): Context {
        // O serviço de acessibilidade já é o "dono" das suas janelas de sobreposição.
        if (accessibility) return svc
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val display = svc.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                return svc.createDisplayContext(display)
                    .createWindowContext(overlayType, null)
            }
        }
        return svc
    }

    private fun baseParams(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(
            1, 1, overlayType, flags, PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        p.windowAnimations = 0
        return p
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createOverlay() {
        if (root != null) return
        pageReady = false
        islandOpen = false
        locateCamera()
        computeGeometry()

        // ----- cápsula (WebView) -----
        val w = WebView(ui)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowContentAccess = false
        w.settings.textZoom = 100
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        w.addJavascriptInterface(Host(), "IslandHost")
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e("IlhaJS", "${m.message()} @${m.lineNumber()}")
                }
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Sem isso o Android encerra o app inteiro quando o renderizador é morto.
                Log.w(TAG, "renderizador da ilha encerrado (crash=${detail.didCrash()}); recriando")
                main.post { recoverFromRendererDeath() }
                return true
            }
        }
        val frame = object : FrameLayout(ui) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    if (islandOpen) js(JSONObject().put("type", "close"))
                    return false
                }
                return super.dispatchTouchEvent(ev)
            }
        }
        frame.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val params = baseParams(false)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        params.title = "Ilha dinâmica — cápsula"
        params.alpha = 0f
        applyCapsuleGeometry(params)
        wm.addView(frame, params)
        root = frame
        web = w
        lp = params

        // ----- bolha (nativa), por cima da cápsula -----
        val b = BubbleView(ui) {
            js(JSONObject().put("type", if (islandOpen) "close" else "open"))
        }
        b.bubbleScale = bubbleScale
        b.contentDescription = "Ilha dinâmica"
        val bp = baseParams(true)
        bp.title = "Ilha dinâmica — bolha"
        applyBubbleGeometry(bp)
        wm.addView(b, bp)
        bubble = b
        bubbleLp = bp

        applyVisibility()
        w.loadUrl("file:///android_asset/island.html")
    }

    private fun destroyOverlay() {
        pageReady = false
        islandOpen = false
        val r = root
        val w = web
        val b = bubble
        root = null
        web = null
        lp = null
        bubble = null
        bubbleLp = null
        if (b != null) runCatching { wm.removeViewImmediate(b) }
        if (r != null) runCatching { wm.removeViewImmediate(r) }
        if (w != null) runCatching {
            w.removeJavascriptInterface("IslandHost")
            w.stopLoading()
            w.destroy()
        }
    }

    private fun recoverFromRendererDeath() {
        if (current !== this) return
        val now = SystemClock.elapsedRealtime()
        rendererDeaths.removeAll { now - it > 60_000 }
        rendererDeaths.add(now)
        destroyOverlay()
        if (rendererDeaths.size > 4) {
            // Algo muito errado no aparelho: desliga com segurança em vez de entrar em loop.
            Log.e(TAG, "renderizador caiu várias vezes; desligando a ilha")
            onFatal()
            return
        }
        main.postDelayed({ if (current === this) runCatching { createOverlay() } }, 400)
    }

    fun onConfigurationChanged() {
        reconfigure(250)
    }

    /** Reaplica posição/tamanho (rotação, ajustes do usuário). */
    fun reconfigure(delayMs: Long = 0) {
        main.postDelayed({
            if (root == null) return@postDelayed
            runCatching {
                locateCamera()
                computeGeometry()
                lp?.let { applyCapsuleGeometry(it); root?.let { r -> wm.updateViewLayout(r, it) } }
                bubbleLp?.let { applyBubbleGeometry(it); bubble?.let { b -> b.bubbleScale = bubbleScale; wm.updateViewLayout(b, it) } }
                applyVisibility()
                pushConfig()
            }
        }, delayMs)
    }

    /** Descobre o centro do furo da câmera (display cutout); sem furo, usa o topo central. */
    private fun locateCamera() {
        val dm = ui.resources.displayMetrics
        screenWpx = dm.widthPixels.toFloat()
        var cutout: android.view.DisplayCutout? = null
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val m = wm.currentWindowMetrics
                screenWpx = m.bounds.width().toFloat()
                cutout = m.windowInsets.displayCutout
            } else if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                cutout = wm.defaultDisplay.cutout
            }
        }
        landscape = ui.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val rect: Rect? = cutout?.boundingRects?.firstOrNull { it.top <= 0 && it.width() < screenWpx * 0.6f && !it.isEmpty }
        if (rect != null) {
            camX = rect.exactCenterX()
            camY = rect.exactCenterY()
            cutoutDp = min(rect.width(), rect.height()) / dm.density
        } else {
            camX = screenWpx / 2f
            camY = statusBarHeight() / 2f
            cutoutDp = 20f
        }
        camX += prefs.offsetX * dm.density
        camY += prefs.offsetY * dm.density
    }

    // Geometria (dp): igual à usada pelo JavaScript para desenhar.
    private var screenWpx = 0f
    private var bubbleScale = 1f
    private var zoom = 1f
    private var winXdp = 0f
    private var winWdp = 0f
    private var winHdp = 0f
    private var capTopDp = 0f
    private var capCxDp = 0f

    private fun computeGeometry() {
        val d = ui.resources.displayMetrics.density
        val wDp = screenWpx / d
        val cx = camX / d
        val cy = camY / d
        // A bolha tem 34 dp em volta de uma câmera de ~20 dp.
        bubbleScale = ((cutoutDp / 20f).coerceIn(0.85f, 1.6f) * prefs.bubbleScale).coerceIn(0.6f, 2.4f)
        zoom = min(prefs.scale, (wDp - 20f) / CAP_W).coerceIn(0.5f, 1.2f)
        val capW = CAP_W * zoom
        winWdp = min(wDp, capW + 56f)
        winXdp = (cx - winWdp / 2f).coerceIn(0f, (wDp - winWdp).coerceAtLeast(0f))
        capTopDp = cy + 17f * bubbleScale + 9f
        winHdp = capTopDp + CAP_H * zoom + 200f
        // A cápsula fica sob a câmera, mas sempre inteira dentro da tela.
        val lo = capW / 2f + 10f
        val hi = wDp - capW / 2f - 10f
        capCxDp = (if (lo <= hi) cx.coerceIn(lo, hi) else wDp / 2f) - winXdp
    }

    private fun applyCapsuleGeometry(p: WindowManager.LayoutParams) {
        p.width = dp(winWdp)
        p.height = dp(winHdp)
        p.x = dp(winXdp)
        p.y = 0
    }

    private fun applyBubbleGeometry(p: WindowManager.LayoutParams) {
        val size = dp(40f * bubbleScale + 6f)
        p.width = size
        p.height = size
        p.x = (camX - size / 2f).roundToInt()
        p.y = (camY - size / 2f).roundToInt()
        Log.i(TAG, "bolha em x=${camX.roundToInt()} y=${camY.roundToInt()}")
    }

    private fun statusBarHeight(): Int {
        @SuppressLint("InternalInsetResource", "DiscouragedApi")
        val id = ui.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ui.resources.getDimensionPixelSize(id) else dp(24f)
    }

    private fun pushConfig() {
        val d = ui.resources.displayMetrics.density
        js(
            JSONObject()
                .put("type", "cfg")
                .put("bx", (camX / d - winXdp).toDouble())
                .put("by", (camY / d).toDouble())
                .put("capCx", capCxDp.toDouble())
                .put("capTop", capTopDp.toDouble())
                .put("z", zoom.toDouble())
                .put("bscale", bubbleScale.toDouble())
        )
    }

    /** Mostra/esconde as camadas conforme o estado (aberta/fechada, retrato/paisagem). */
    private fun applyVisibility() {
        val b = bubble
        val r = root
        val p = lp
        b?.visibility = if (landscape) View.GONE else View.VISIBLE
        if (r != null && p != null) {
            val show = islandOpen && !landscape
            val wantAlpha = if (show) 1f else 0f
            val touchFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val wantFlags = if (show) p.flags and touchFlag.inv() else p.flags or touchFlag
            if (p.alpha != wantAlpha || p.flags != wantFlags) {
                p.alpha = wantAlpha
                p.flags = wantFlags
                runCatching { wm.updateViewLayout(r, p) }
            }
            r.visibility = if (landscape) View.GONE else View.VISIBLE
        }
    }

    fun js(cmd: JSONObject) {
        val s = cmd.toString()
        main.post {
            val w = web
            if (w == null || !pageReady) {
                if (pending.size < 50) pending.add(s)
                return@post
            }
            runCatching { w.evaluateJavascript("window.island&&island.cmd($s)", null) }
        }
    }

    private fun dp(v: Float): Int = (v * (if (::ui.isInitialized) ui else svc).resources.displayMetrics.density).roundToInt()

    // ---------------------------------------------------------------- ponte JS → Android

    inner class Host {
        @JavascriptInterface
        fun ready() {
            main.post {
                pageReady = true
                runCatching { pushConfig() }
                val copy = ArrayList(pending)
                pending.clear()
                copy.forEach { s -> runCatching { web?.evaluateJavascript("window.island&&island.cmd($s)", null) } }
                refreshMedia()
                runCatching { pushBrightness() }
                pushCalendar()
            }
        }

        /** A cápsula está visível (aberta ou animando o fechamento)? */
        @JavascriptInterface
        fun setOpen(open: Boolean) {
            main.post {
                islandOpen = open
                applyVisibility()
            }
        }

        /** Cor (#RRGGBB) e progresso (0–1) do anel da bolha. */
        @JavascriptInterface
        fun bubble(color: String, pct: Float) {
            val c = runCatching { Color.parseColor(color) }.getOrNull() ?: return
            main.post { bubble?.setState(c, if (pct.isNaN()) 0f else pct) }
        }

        @JavascriptInterface
        fun report(json: String) {
            lastState = json
        }

        @JavascriptInterface
        fun tick() {
            main.post { root?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        }

        @JavascriptInterface
        fun media(action: String) {
            main.post { runCatching { mediaAction(action) } }
        }

        @JavascriptInterface
        fun setVolume(pct: Int) {
            runCatching {
                val am = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (pct.coerceIn(0, 100) * max / 100f).roundToInt(), 0)
            }
        }

        @JavascriptInterface
        fun setBrightness(pct: Int, mode: Int) {
            runCatching {
                if (!Settings.System.canWrite(svc)) return
                val cr = svc.contentResolver
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
            main.post { runCatching { feedback(kind) } }
        }

        @JavascriptInterface
        fun openApp(tab: String) {
            main.post { runCatching { launchFor(tab) } }
        }
    }

    // ---------------------------------------------------------------- feedback

    private fun vibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            (svc.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            svc.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

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
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun playTone(type: Int, maxMs: Long) {
        runCatching {
            stopRing()
            val r = RingtoneManager.getRingtone(svc, RingtoneManager.getDefaultUri(type)) ?: return
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
        val pm = svc.packageManager
        val intent: Intent? = when (tab) {
            "cal" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
            "maps" -> pm.getLaunchIntentForPackage("com.google.android.apps.maps")
                ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)
            "ifood" -> pm.getLaunchIntentForPackage("br.com.brainweb.ifood")
            else -> NotifListener.packagesFor(tab).firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        }
        intent ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { svc.startActivity(intent) }
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
            runCatching {
                val msm = svc.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val list = msm.getActiveSessions(ComponentName(svc, NotifListener::class.java))
                val pick = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
                if (pick?.sessionToken != controller?.sessionToken) {
                    runCatching { controller?.unregisterCallback(mediaCallback) }
                    controller = pick
                    pick?.registerCallback(mediaCallback, main)
                    lastArtKey = null
                }
                if (pick == null) js(JSONObject().put("type", "media").put("src", "none"))
                else pushMedia(false)
            } // SecurityException sem acesso às notificações: simplesmente sem mídia real.
        }
    }

    private fun pushMedia(show: Boolean) {
        runCatching {
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
            val am = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val o = JSONObject()
                .put("type", "media")
                .put("src", if (isYt) "yt" else "music")
                .put("title", title)
                .put("artist", artist)
                .put("playing", playing)
                .put("pos", (posMs / 1000).coerceAtLeast(0))
                .put("dur", (dur / 1000).coerceAtLeast(0))
                .put("vol", vol)
                .put("show", show && playing)
            val artKey = c.packageName + "|" + title
            if (artKey != lastArtKey) {
                lastArtKey = artKey
                val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                o.put("art", Img.toDataUrl(bmp) ?: "")
            }
            js(o)
        }
    }

    private fun mediaAction(action: String) {
        val tc = controller?.transportControls ?: return
        when {
            action == "play" -> tc.play()
            action == "pause" -> tc.pause()
            action == "next" -> tc.skipToNext()
            action == "prev" -> tc.skipToPrevious()
            action.startsWith("seek:") -> action.removePrefix("seek:").toLongOrNull()?.let { tc.seekTo(it) }
        }
    }

    // ---------------------------------------------------------------- brilho e calendário

    private fun pushBrightness() {
        val cr = svc.contentResolver
        val v = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        js(JSONObject().put("type", "brightness").put("value", (v * 100 / 255f).roundToInt()).put("auto", auto))
    }

    /** Lê os eventos desta semana (seg–dom) do calendário do aparelho. */
    fun pushCalendar() {
        if (svc.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        Thread {
            runCatching {
                val cal = Calendar.getInstance()
                val todayIdx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.add(Calendar.DAY_OF_MONTH, -todayIdx)
                val start = cal.timeInMillis
                val end = start + 7L * 24 * 3600 * 1000
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                    ContentUris.appendId(it, start)
                    ContentUris.appendId(it, end)
                }.build()
                val days = Array(7) { ArrayList<String>() }
                var next: Long = Long.MAX_VALUE
                val now = System.currentTimeMillis()
                svc.contentResolver.query(
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
