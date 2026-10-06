package com.enzo.ilhadinamica

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log

/**
 * Modo alternativo: hospeda a ilha num serviço em primeiro plano usando "aparecer sobre outros apps".
 * Quando a acessibilidade da Ilha está ativa, quem hospeda é o [IslandA11y] e este serviço se encerra.
 *
 * startForeground() é chamado em TODA chamada de onStartCommand, evitando o encerramento forçado
 * "did not then call startForeground".
 */
class IslandService : Service() {

    companion object {
        private const val TAG = "Ilha"
        const val ACTION_STOP = "com.enzo.ilhadinamica.STOP"
        private const val CHANNEL = "ilha"
        private const val NOTIF_ID = 26

        @Volatile
        var running: Boolean = false
            private set

        /** Liga a ilha pelo melhor caminho disponível. */
        fun start(ctx: Context) {
            if (IslandA11y.instance != null) {
                IslandA11y.instance?.refresh()
                return
            }
            runCatching { ctx.startForegroundService(Intent(ctx, IslandService::class.java)) }
                .onFailure { Log.w(TAG, "não foi possível iniciar", it) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, IslandService::class.java)) }
            IslandA11y.instance?.refresh()
        }
    }

    private var island: Island? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startAsForeground()
        if (IslandA11y.instance != null || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val i = Island(this, accessibility = false) { stopSelf() }
        island = i
        if (!i.start()) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (intent?.action == ACTION_STOP) {
            IslandPrefs(this).enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (IslandA11y.instance != null) {
            // A acessibilidade assumiu a ilha.
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        island?.onConfigurationChanged()
    }

    override fun onDestroy() {
        running = false
        island?.stop()
        island = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                }
            )
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val stop = PendingIntent.getService(
                this, 1, Intent(this, IslandService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_island)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        }.onFailure { Log.e(TAG, "startForeground falhou", it) }
    }
}
