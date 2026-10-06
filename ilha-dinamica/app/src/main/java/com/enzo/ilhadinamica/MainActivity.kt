package com.enzo.ilhadinamica

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** Painel de controle da ilha, no visual do protótipo "Bolha da câmera". */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var prefs: IslandPrefs

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = IslandPrefs(this)
        window.statusBarColor = Color.parseColor("#ECEDE8")
        window.navigationBarColor = Color.parseColor("#ECEDE8")
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#ECEDE8"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.textZoom = 100
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // O Android mataria o app inteiro; em vez disso, recria a tela.
                if (!isFinishing && !isDestroyed) recreate()
                return true
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/panel.html")

        // Primeira abertura: se tudo já estiver liberado, liga a ilha direto.
        if (prefs.enabled && Settings.canDrawOverlays(this) && IslandService.instance == null) {
            IslandService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching { web.evaluateJavascript("window.panel&&panel.refresh()", null) }
        IslandService.instance?.let {
            it.refreshMedia()
            it.pushCalendar()
        }
    }

    override fun onDestroy() {
        runCatching {
            web.removeJavascriptInterface("Android")
            web.destroy()
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        IslandService.instance?.pushCalendar()
        runCatching { web.evaluateJavascript("window.panel&&panel.refresh()", null) }
    }

    private fun notifAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, NotifListener::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun cmd(o: JSONObject) {
        IslandService.send(o)
    }

    inner class Bridge {
        @JavascriptInterface
        fun getState(): String {
            val st = runCatching { JSONObject(IslandService.lastState) }.getOrDefault(JSONObject())
            return JSONObject()
                .put("overlay", Settings.canDrawOverlays(this@MainActivity))
                .put("notif", notifAccess())
                .put("calendar", granted(Manifest.permission.READ_CALENDAR))
                .put("write", Settings.System.canWrite(this@MainActivity))
                .put("post", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
                .put("running", IslandService.instance != null)
                .put("enabled", prefs.enabled)
                .put("tab", st.optString("tab", "music"))
                .put("open", st.optBoolean("open", false))
                .put("scale", prefs.scale.toDouble())
                .put("bscale", prefs.bubbleScale.toDouble())
                .put("dx", prefs.offsetX.toDouble())
                .put("dy", prefs.offsetY.toDouble())
                .put("real", prefs.realEvents)
                .toString()
        }

        @JavascriptInterface
        fun requestOverlay() = runOnUiThread {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                .onFailure { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } }
        }

        @JavascriptInterface
        fun requestNotifAccess() = runOnUiThread {
            val i = if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this@MainActivity, NotifListener::class.java).flattenToString())
            } else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            runCatching { startActivity(i) }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
        }

        @JavascriptInterface
        fun requestCalendar() = runOnUiThread {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 2)
        }

        @JavascriptInterface
        fun requestWrite() = runOnUiThread {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
        }

        @JavascriptInterface
        fun setEnabled(on: Boolean) = runOnUiThread {
            if (on) {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    requestOverlay()
                    return@runOnUiThread
                }
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                prefs.enabled = true
                if (IslandService.instance == null) IslandService.start(this@MainActivity)
            } else {
                prefs.enabled = false
                IslandService.stop(this@MainActivity)
            }
        }

        @JavascriptInterface
        fun setTab(id: String) = cmd(JSONObject().put("type", "tab").put("id", id))

        @JavascriptInterface
        fun setOpen(open: Boolean) = cmd(JSONObject().put("type", if (open) "open" else "close"))

        @JavascriptInterface
        fun setScale(v: Float) {
            prefs.scale = v
            IslandService.instance?.reconfigure()
        }

        @JavascriptInterface
        fun setBubbleScale(v: Float) {
            prefs.bubbleScale = v
            IslandService.instance?.reconfigure()
        }

        @JavascriptInterface
        fun setOffset(dx: Float, dy: Float) {
            prefs.offsetX = dx
            prefs.offsetY = dy
            IslandService.instance?.reconfigure()
        }

        @JavascriptInterface
        fun setReal(on: Boolean) {
            prefs.realEvents = on
        }
    }
}
