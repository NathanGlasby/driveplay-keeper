package za.co.driveplaykeeper

class ResumeDecisionEngine(private val clock: () -> Long) {
    enum class Decision { RESUME, DISABLED, NO_ANDROID_AUTO, NOT_ARMED, SUPPRESSED, NO_RECENT_POWER_EVENT }
    private var playingSinceMs: Long? = null
    private var lastAutoResumeMs: Long? = null
    private var lastPowerEventMs: Long? = null
    private var suppressUntilPlaybackRestarts = false

    fun onPlaying() { if (playingSinceMs == null) playingSinceMs = clock(); suppressUntilPlaybackRestarts = false }
    fun onNotPlaying() { playingSinceMs = null }
    fun onPowerEvent() { lastPowerEventMs = clock() }
    fun onAndroidAutoDisconnected() { playingSinceMs = null; lastAutoResumeMs = null; lastPowerEventMs = null; suppressUntilPlaybackRestarts = true }

    fun decide(enabled: Boolean, androidAutoConnected: Boolean, requireAndroidAuto: Boolean, requirePowerEvent: Boolean, minimumPlayingTimeMs: Long, manualPauseWindowMs: Long, powerEventWindowMs: Long = 10_000L): Decision {
        val now = clock()
        val startedAt = playingSinceMs ?: return Decision.NOT_ARMED
        if (!enabled) return Decision.DISABLED
        if (requireAndroidAuto && !androidAutoConnected) return Decision.NO_ANDROID_AUTO
        if (now - startedAt < minimumPlayingTimeMs) return Decision.NOT_ARMED
        if (suppressUntilPlaybackRestarts) return Decision.SUPPRESSED
        val previousResume = lastAutoResumeMs
        if (previousResume != null && now - previousResume <= manualPauseWindowMs) { suppressUntilPlaybackRestarts = true; return Decision.SUPPRESSED }
        if (requirePowerEvent) {
            val powerEvent = lastPowerEventMs ?: return Decision.NO_RECENT_POWER_EVENT
            if (now - powerEvent > powerEventWindowMs) return Decision.NO_RECENT_POWER_EVENT
        }
        lastAutoResumeMs = now
        return Decision.RESUME
    }

    fun shouldResume(enabled: Boolean, androidAutoConnected: Boolean, requireAndroidAuto: Boolean, requirePowerEvent: Boolean, minimumPlayingTimeMs: Long = AppPreferences.DEFAULT_MINIMUM_PLAYING_TIME_MS, manualPauseWindowMs: Long = AppPreferences.DEFAULT_MANUAL_PAUSE_WINDOW_MS) = decide(enabled, androidAutoConnected, requireAndroidAuto, requirePowerEvent, minimumPlayingTimeMs, manualPauseWindowMs) == Decision.RESUME
    fun reset() { playingSinceMs = null; lastAutoResumeMs = null; lastPowerEventMs = null; suppressUntilPlaybackRestarts = false }
}
