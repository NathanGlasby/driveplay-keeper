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

    companion object {
        private const val FILE_NAME = "drive_play_keeper"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_REQUIRE_ANDROID_AUTO = "require_android_auto"
        private const val KEY_REQUIRE_POWER_EVENT = "require_power_event"
        private const val KEY_RESUME_DELAY_MS = "resume_delay_ms"

        const val DEFAULT_RESUME_DELAY_MS = 900L
    }
}
