package com.enzo.ilhadinamica

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Modo recomendado ("de fábrica"): a ilha hospedada pelo serviço de acessibilidade.
 *
 * Janelas de acessibilidade ficam ACIMA da barra de status, então a bolha em volta da câmera
 * recebe o toque normalmente; e o sistema mantém este serviço sempre ativo (não é encerrado
 * em segundo plano). A Ilha não lê o conteúdo da tela: não pede eventos nem conteúdo de janelas.
 */
class IslandA11y : AccessibilityService() {

    companion object {
        @Volatile
        var instance: IslandA11y? = null
            private set
    }

    private var island: Island? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("Ilha", "acessibilidade conectada")
        // Assume a ilha no lugar do serviço comum (se estiver rodando).
        if (IslandService.running) runCatching { stopService(Intent(this, IslandService::class.java)) }
        refresh()
    }

    /** Liga/desliga a ilha conforme a preferência "Ilha ligada". */
    fun refresh() {
        val on = IslandPrefs(this).enabled
        if (on && island == null) {
            val i = Island(this, accessibility = true) { island?.stop(); island = null }
            island = i
            if (!i.start()) island = null
        } else if (!on && island != null) {
            island?.stop()
            island = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        island?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        island?.stop()
        island = null
        // Acessibilidade desligada: volta para o modo comum, se a ilha estiver ligada.
        val prefs = IslandPrefs(this)
        if (prefs.enabled && android.provider.Settings.canDrawOverlays(this)) {
            IslandService.start(this)
        }
    }
}
