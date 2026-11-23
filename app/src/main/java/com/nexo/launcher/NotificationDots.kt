package com.nexo.launcher

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import androidx.compose.runtime.mutableStateMapOf

/** Stores only per-package active-notification counts; no titles, text, senders, or notification payloads. */
object NotificationDots {
    val counts = mutableStateMapOf<String, Int>()

    fun hasAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val component = ComponentName(context, NotificationDotListener::class.java).flattenToString()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    internal fun replaceFrom(notifications: Array<StatusBarNotification>) {
        counts.clear()
        notifications.asSequence()
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .groupingBy { it.packageName }
            .eachCount()
            .forEach { (pkg, count) -> counts[pkg] = count }
    }
}

class NotificationDotListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching { NotificationDots.replaceFrom(activeNotifications.orEmpty()) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { NotificationDots.replaceFrom(activeNotifications.orEmpty()) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching { NotificationDots.replaceFrom(activeNotifications.orEmpty()) }
    }

    override fun onListenerDisconnected() {
        NotificationDots.counts.clear()
        super.onListenerDisconnected()
    }
}
