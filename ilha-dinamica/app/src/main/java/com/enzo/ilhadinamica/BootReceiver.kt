package com.enzo.ilhadinamica

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Religa a ilha depois que o celular reinicia, se ela estava ligada. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (IslandPrefs(context).enabled && Settings.canDrawOverlays(context)) {
            runCatching { IslandService.start(context) }
        }
    }
}
