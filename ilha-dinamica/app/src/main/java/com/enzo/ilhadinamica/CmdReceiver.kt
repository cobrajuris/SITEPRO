package com.enzo.ilhadinamica

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Comandos para os testes automáticos (via adb como root; o receptor não é exportado,
 * então outros apps não conseguem usá-lo): "tab:wa", "open", "close", "toggle",
 * "shelltest:on/off".
 */
class CmdReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = intent.getStringExtra("c") ?: return
        when (c) {
            "shelltest:on" -> IslandPrefs(context).shellTest = true
            "shelltest:off" -> IslandPrefs(context).shellTest = false
            "enable" -> { IslandPrefs(context).enabled = true; IslandService.start(context) }
            "disable" -> { IslandPrefs(context).enabled = false; IslandService.stop(context) }
            else -> Island.current?.shortCommand(c)
        }
    }
}
