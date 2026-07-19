package za.co.driveplaykeeper

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService

class ListenerRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val afterUpdate = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> false
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            else -> return
        }
        AppPreferences(context).recordRecoveryRequested(afterUpdate = afterUpdate)

        val listener = ComponentName(context, SpotifyPlaybackListener::class.java)
        NotificationListenerService.requestRebind(listener)
    }
}
