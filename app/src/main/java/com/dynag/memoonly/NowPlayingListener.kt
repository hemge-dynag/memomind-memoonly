package com.dynag.memoonly

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Notification listener, present only to obtain the one permission that lets
 * a third-party app read the system MediaSessions
 * ([android.media.session.MediaSessionManager.getActiveSessions] is gated on a
 * granted listener component).
 *
 * It forwards session changes to the running mirror service so a new track is
 * picked up immediately instead of on the next one-second poll. Everything
 * else a notification listener could read is ignored.
 */
class NowPlayingListener : NotificationListenerService() {

    companion object {
        private const val tag = "NowPlayingListener"

        /** True when the user enabled this listener in Settings. */
        fun isGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val component = ComponentName(context, NowPlayingListener::class.java)
            return flat.split(':').any {
                val parsed = ComponentName.unflattenFromString(it) ?: return@any false
                parsed == component
            }
        }

        /** Open the system screen where the listener is enabled. */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    override fun onListenerConnected() {
        Log.d(tag, "listener connected")
        MusicMirrorService.notifySessionsChanged()
    }

    override fun onListenerDisconnected() {
        Log.d(tag, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        MusicMirrorService.notifySessionsChanged()
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {
        MusicMirrorService.notifySessionsChanged()
    }
}
