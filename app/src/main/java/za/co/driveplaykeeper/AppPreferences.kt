package za.co.driveplaykeeper

import android.content.Context

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    var requireAndroidAuto: Boolean
        get() = preferences.getBoolean(KEY_REQUIRE_ANDROID_AUTO, true)
        set(value) = preferences.edit().putBoolean(KEY_REQUIRE_ANDROID_AUTO, value).apply()

    var requirePowerEvent: Boolean
        get() = preferences.getBoolean(KEY_REQUIRE_POWER_EVENT, false)
        set(value) = preferences.edit().putBoolean(KEY_REQUIRE_POWER_EVENT, value).apply()

    var resumeDelayMs: Long
        get() = preferences.getLong(KEY_RESUME_DELAY_MS, DEFAULT_RESUME_DELAY_MS)
        set(value) = preferences.edit().putLong(KEY_RESUME_DELAY_MS, value).apply()

    val listenerLifecycleEvent: String?
        get() = preferences.getString(KEY_LISTENER_LIFECYCLE_EVENT, null)

    val listenerLifecycleAt: Long
        get() = preferences.getLong(KEY_LISTENER_LIFECYCLE_AT, 0L)

    fun recordListenerConnected(at: Long = System.currentTimeMillis()) {
        recordListenerLifecycle(EVENT_CONNECTED, at)
    }

    fun recordListenerDisconnected(at: Long = System.currentTimeMillis()) {
        recordListenerLifecycle(EVENT_DISCONNECTED, at)
    }

    fun recordListenerDestroyed(at: Long = System.currentTimeMillis()) {
        recordListenerLifecycle(EVENT_DESTROYED, at)
    }

    fun recordRecoveryRequested(afterUpdate: Boolean, at: Long = System.currentTimeMillis()) {
        recordListenerLifecycle(
            if (afterUpdate) EVENT_RECOVERY_AFTER_UPDATE else EVENT_RECOVERY_AFTER_RESTART,
            at,
        )
    }

    private fun recordListenerLifecycle(event: String, at: Long) {
        preferences.edit()
            .putString(KEY_LISTENER_LIFECYCLE_EVENT, event)
            .putLong(KEY_LISTENER_LIFECYCLE_AT, at)
            .apply()
    }

    companion object {
        private const val FILE_NAME = "drive_play_keeper"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_REQUIRE_ANDROID_AUTO = "require_android_auto"
        private const val KEY_REQUIRE_POWER_EVENT = "require_power_event"
        private const val KEY_RESUME_DELAY_MS = "resume_delay_ms"
        private const val KEY_LISTENER_LIFECYCLE_EVENT = "listener_lifecycle_event"
        private const val KEY_LISTENER_LIFECYCLE_AT = "listener_lifecycle_at"

        const val EVENT_CONNECTED = "connected"
        const val EVENT_DISCONNECTED = "disconnected"
        const val EVENT_DESTROYED = "destroyed"
        const val EVENT_RECOVERY_AFTER_RESTART = "recovery_after_restart"
        const val EVENT_RECOVERY_AFTER_UPDATE = "recovery_after_update"

        const val DEFAULT_RESUME_DELAY_MS = 900L
    }
}
