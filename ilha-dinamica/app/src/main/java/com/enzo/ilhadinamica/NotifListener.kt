package com.enzo.ilhadinamica

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

/**
 * Lê as notificações reais (WhatsApp, Instagram, Telegram, TikTok, iFood, Maps, YouTube)
 * e as mostra na cápsula — com a foto do contato ou do grupo. Também dá acesso às sessões
 * de mídia para a aba Música.
 */
class NotifListener : NotificationListenerService() {

    companion object {
        private const val TAG = "IlhaNotif"
        private val MAP = mapOf(
            "com.whatsapp" to "wa",
            "com.whatsapp.w4b" to "wa",
            "com.instagram.android" to "ig",
            "com.instagram.barcelona" to "ig",
            "org.telegram.messenger" to "tg",
            "org.telegram.messenger.web" to "tg",
            "org.thunderdog.challegram" to "tg",
            "com.zhiliaoapp.musically" to "tt",
            "com.ss.android.ugc.trill" to "tt",
            "br.com.brainweb.ifood" to "ifood",
            "com.google.android.apps.maps" to "maps",
            "com.google.android.youtube" to "yt"
        )

        fun packagesFor(tab: String): List<String> = MAP.filterValues { it == tab }.keys.toList()

        private val lastKey = HashMap<String, String>()
    }

    override fun onListenerConnected() {
        IslandService.instance?.refreshMedia()
    }

    override fun onListenerDisconnected() {
        // Alguns aparelhos desconectam o leitor; pede para religar.
        runCatching { requestRebind(ComponentName(this, NotifListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { handle(sbn) }.onFailure { Log.w(TAG, "notificação ignorada", it) }
    }

    private fun handle(sbn: StatusBarNotification) {
        val svc = IslandService.instance ?: return
        val n = sbn.notification ?: return
        if (n.category == Notification.CATEGORY_TRANSPORT) {
            svc.refreshMedia()
            return
        }
        val prefs = IslandPrefs(this)
        if (!prefs.realEvents) return
        var tab = MAP[sbn.packageName]
            ?: (if (prefs.shellTest && sbn.packageName == "com.android.shell") "wa" else null)
            ?: return
        if (tab == "yt") return // o vídeo em reprodução chega pela sessão de mídia
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val ex = n.extras ?: return
        val convTitle = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        var title = convTitle ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        var text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val isGroup = convTitle != null ||
            (Build.VERSION.SDK_INT >= 28 && ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false))

        // Última mensagem da conversa (MessagingStyle): quem mandou, o texto e a foto de quem mandou.
        var senderIcon: Icon? = null
        @Suppress("DEPRECATION")
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = msgs?.lastOrNull() as? Bundle
        if (last != null) {
            val msgText = last.getCharSequence("text")?.toString()
            var sender = last.getCharSequence("sender")?.toString()
            if (Build.VERSION.SDK_INT >= 28) {
                @Suppress("DEPRECATION")
                val p = runCatching { last.getParcelable<Person>("sender_person") }.getOrNull()
                if (p != null) {
                    if (sender.isNullOrBlank()) sender = p.name?.toString()
                    senderIcon = p.icon
                }
            }
            if (!msgText.isNullOrBlank()) {
                text = if (isGroup && !sender.isNullOrBlank()) "$sender: $msgText" else msgText
            }
            if (convTitle == null && !sender.isNullOrBlank()) title = sender
        }

        if (n.category == Notification.CATEGORY_CALL && tab == "wa") tab = "call"
        if (title.isBlank() && text.isBlank()) return

        // Evita reabrir a cápsula para a mesma notificação apenas atualizada.
        val key = sbn.key + "|" + title + "|" + text
        if (lastKey[sbn.key] == key) return
        lastKey[sbn.key] = key
        if (lastKey.size > 200) lastKey.clear()

        // Foto: a do grupo/contato (ícone grande) e, se não houver, a de quem mandou.
        val avatar = Img.fromIcon(this, n.getLargeIcon())
            ?: largeIconBitmap(ex)
            ?: Img.fromIcon(this, senderIcon)

        val o = JSONObject()
            .put("type", "notify")
            .put("tab", tab)
            .put("title", title)
            .put("text", text)
        if (avatar != null) o.put("avatar", avatar)
        if (tab == "call") {
            o.put("app", if (sbn.packageName == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp")
            o.put("hold", 20000)
        }
        if (tab == "ifood" || tab == "maps") {
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { o.put("sub", it.toString()) }
        }
        svc.js(o)
    }

    private fun largeIconBitmap(ex: Bundle): String? = runCatching {
        @Suppress("DEPRECATION")
        val b = ex.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
        Img.toDataUrl(b)
    }.getOrNull()

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastKey.remove(sbn.key)
    }
}
