package com.enzo.ilhadinamica

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * Lê as notificações reais (WhatsApp, Instagram, Telegram, TikTok, iFood, Maps, YouTube)
 * e as mostra na cápsula. Também dá acesso às sessões de mídia para a aba Música.
 */
class NotifListener : NotificationListenerService() {

    companion object {
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

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val svc = IslandService.instance ?: return
        if (sbn.packageName != packageName && sbn.notification.category == Notification.CATEGORY_TRANSPORT) {
            svc.refreshMedia()
        }
        if (!IslandPrefs(this).realEvents) return
        var tab = MAP[sbn.packageName] ?: return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        var title = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        var text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""

        // Última mensagem de uma conversa (MessagingStyle).
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            val last = msgs.last() as? android.os.Bundle
            if (last != null) {
                last.getCharSequence("text")?.let { text = it.toString() }
                val sender = last.getCharSequence("sender")?.toString()
                if (!sender.isNullOrBlank() && ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) == null) title = sender
            }
        }

        if (n.category == Notification.CATEGORY_CALL && tab == "wa") tab = "call"
        if (tab == "yt") {
            // Vídeo em reprodução chega pela sessão de mídia; notificações comuns do YouTube são ignoradas.
            if (n.category == Notification.CATEGORY_TRANSPORT) svc.refreshMedia()
            return
        }
        if (n.category == Notification.CATEGORY_TRANSPORT) return
        if (title.isBlank() && text.isBlank()) return

        // Evita reabrir a cápsula para a mesma notificação atualizada.
        val key = sbn.key + "|" + title + "|" + text
        if (lastKey[sbn.key] == key) return
        lastKey[sbn.key] = key

        val o = JSONObject()
            .put("type", "notify")
            .put("tab", tab)
            .put("title", title)
            .put("text", text)
        if (tab == "call") {
            o.put("app", if (sbn.packageName == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp")
            o.put("hold", 20000)
        }
        if (tab == "ifood" || tab == "maps") {
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { o.put("sub", it.toString()) }
        }
        svc.js(o)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastKey.remove(sbn.key)
    }
}
