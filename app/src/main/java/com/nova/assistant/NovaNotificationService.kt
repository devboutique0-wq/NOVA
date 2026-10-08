package com.nova.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Driving mode: sees notifications ONLY from the allow-listed messaging apps (Driving.APPS) and ONLY while the user
 * has switched driving mode on. Text is kept in memory, never logged, never uploaded.
 * The user must switch on "Notification access" for NOVA in Android settings (NOVA cannot do that by itself).
 */
class NovaNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null || !Driving.enabled) return
            val pkg = sbn.packageName ?: return
            if (pkg == packageName || pkg !in Driving.APPS) return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
            val ex = n.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            val action = DrivingBridge.replyAction(n)
            val m = Driving.makeMsg(sbn.key ?: "$pkg:${sbn.id}", pkg, title, body, System.currentTimeMillis(), action != null) ?: return
            if (action != null) DrivingBridge.remember(m.key, action)
            if (Driving.inbox.offer(m)) NovaService.instance?.onDrivingMessage(m)
        } catch (e: Exception) {
            // never crash the listener because of one odd notification
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val k = sbn?.key ?: return
        DrivingBridge.forget(k)
    }
}

/** Android-side helpers for driving mode (the pure rules are in Driving.kt). */
object DrivingBridge {
    private const val MAX_ACTIONS = 12
    private val actions = ConcurrentHashMap<String, Notification.Action>()
    private val order = ArrayList<String>()

    /** The notification's inline "Reply" action, if it has one. */
    fun replyAction(n: Notification): Notification.Action? {
        val list = n.actions ?: return null
        for (a in list) {
            val ri = a.remoteInputs
            if (ri != null && ri.isNotEmpty() && a.actionIntent != null) return a
        }
        return null
    }

    fun remember(key: String, a: Notification.Action) {
        synchronized(order) {
            order.remove(key)
            order.add(key)
            actions[key] = a
            while (order.size > MAX_ACTIONS) actions.remove(order.removeAt(0))
        }
    }

    fun forget(key: String) {
        synchronized(order) { order.remove(key); actions.remove(key) }
    }

    fun forgetAll() {
        synchronized(order) { order.clear(); actions.clear() }
    }

    /** Sends [text] through the notification's own reply button. Call ONLY after the user's spoken yes. */
    fun sendReply(ctx: Context, key: String, text: String): Boolean {
        val a = actions[key] ?: return false
        val inputs = a.remoteInputs ?: return false
        if (inputs.isEmpty()) return false
        val results = Bundle()
        for (ri in inputs) results.putCharSequence(ri.resultKey, text)
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            a.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }

    fun listenerEnabled(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return s.split(":").any { it.startsWith(ctx.packageName + "/") || it.substringBefore("/") == ctx.packageName }
    }

    fun openListenerSettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // no settings screen on this device: the spoken reply still tells the user what to do
        }
    }
}
