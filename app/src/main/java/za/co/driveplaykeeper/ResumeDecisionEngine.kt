package za.co.driveplaykeeper

/**
 * Small, Android-free state machine so the safety behaviour can be unit tested.
 *
 * A first pause is auto-resumed. A second pause shortly after that resume is
 * treated as deliberate and disables protection until playback starts again.
 */
class ResumeDecisionEngine(
    private val clock: () -> Long,
    private val manualPauseWindowMs: Long = 8_000L,
    private val minimumPlayingTimeMs: Long = 4_000L,
    private val powerEventWindowMs: Long = 10_000L,
) {
    private var playingSinceMs: Long? = null
    private var lastAutoResumeMs: Long? = null
    private var lastPowerEventMs: Long? = null
    private var suppressUntilPlaybackRestarts = false

    fun onPlaying() {
        if (playingSinceMs == null) {
            playingSinceMs = clock()
        }
        suppressUntilPlaybackRestarts = false
    }

    fun onNotPlaying() {
        playingSinceMs = null
    }

    fun onPowerEvent() {
        lastPowerEventMs = clock()
    }

    fun shouldResume(
        enabled: Boolean,
        androidAutoConnected: Boolean,
        requireAndroidAuto: Boolean,
        requirePowerEvent: Boolean,
    ): Boolean {
        val now = clock()
        val startedAt = playingSinceMs ?: return false

        if (!enabled || (requireAndroidAuto && !androidAutoConnected)) return false
        if (now - startedAt < minimumPlayingTimeMs) return false
        if (suppressUntilPlaybackRestarts) return false

        val previousResume = lastAutoResumeMs
        if (previousResume != null && now - previousResume <= manualPauseWindowMs) {
            suppressUntilPlaybackRestarts = true
            return false
        }

        if (requirePowerEvent) {
            val powerEvent = lastPowerEventMs ?: return false
            if (now - powerEvent > powerEventWindowMs) return false
        }

        lastAutoResumeMs = now
        return true
    }

    fun reset() {
        playingSinceMs = null
        lastAutoResumeMs = null
        lastPowerEventMs = null
        suppressUntilPlaybackRestarts = false
    }
}
