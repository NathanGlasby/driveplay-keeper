package za.co.driveplaykeeper

class ResumeDecisionEngine(private val clock: () -> Long) {
    enum class Decision { RESUME, DISABLED, NO_ANDROID_AUTO, NOT_ARMED, SUPPRESSED, NO_RECENT_POWER_EVENT, CAR_CONNECTION_MOVED }

    /** Signals that the car's connection (projection or audio path) is leaving this phone. */
    enum class CarLinkSignal { ANDROID_AUTO_DISCONNECTED, CAR_AUDIO_OUTPUT_REMOVED, AUDIO_BECOMING_NOISY, CAR_MODE_EXITED }

    /** What the caller should do after a car-link loss signal. */
    enum class CarLinkLossAction { NONE, CANCEL_PENDING_RESUME, UNDO_AUTO_RESUME }

    private var playingSinceMs: Long? = null
    private var lastAutoResumeMs: Long? = null
    private var lastPowerEventMs: Long? = null
    private var suppressUntilPlaybackRestarts = false
    private var lastCarLinkLossMs: Long? = null
    private var pendingResumePauseMs: Long? = null

    var lastCarLinkSignal: CarLinkSignal? = null
        private set

    fun onPlaying() { if (playingSinceMs == null) playingSinceMs = clock(); suppressUntilPlaybackRestarts = false }
    fun onNotPlaying() { playingSinceMs = null }
    fun onPowerEvent() { lastPowerEventMs = clock() }
    fun onAutoResume() { lastAutoResumeMs = clock(); pendingResumePauseMs = null }
    fun onPendingResumeCancelled() { pendingResumePauseMs = null }
    fun onAndroidAutoDisconnected() { playingSinceMs = null; lastAutoResumeMs = null; lastPowerEventMs = null; pendingResumePauseMs = null; suppressUntilPlaybackRestarts = true }

    /**
     * Records that the car connection is leaving this phone, for example because another phone
     * takes over the car connection. Returns what should happen to a resume that is waiting to be
     * sent, or to one that was sent moments ago.
     */
    fun onCarLinkLost(signal: CarLinkSignal, undoWindowMs: Long = DEFAULT_UNDO_WINDOW_MS): CarLinkLossAction {
        val now = clock()
        lastCarLinkLossMs = now
        lastCarLinkSignal = signal
        val hadPendingResume = pendingResumePauseMs != null
        val recentAutoResume = lastAutoResumeMs?.let { now - it <= undoWindowMs } == true
        pendingResumePauseMs = null
        playingSinceMs = null
        suppressUntilPlaybackRestarts = true
        return when {
            hadPendingResume -> CarLinkLossAction.CANCEL_PENDING_RESUME
            recentAutoResume -> { lastAutoResumeMs = null; CarLinkLossAction.UNDO_AUTO_RESUME }
            else -> CarLinkLossAction.NONE
        }
    }

    fun decide(
        enabled: Boolean,
        androidAutoConnected: Boolean,
        requireAndroidAuto: Boolean,
        requirePowerEvent: Boolean,
        minimumPlayingTimeMs: Long,
        manualPauseWindowMs: Long,
        powerEventWindowMs: Long = 10_000L,
        carLinkLookbackMs: Long = DEFAULT_CAR_LINK_LOOKBACK_MS,
    ): Decision {
        val now = clock()
        pendingResumePauseMs = null
        if (!enabled) return Decision.DISABLED
        if (carLinkLostSince(now - carLinkLookbackMs)) { playingSinceMs = null; return Decision.CAR_CONNECTION_MOVED }
        val startedAt = playingSinceMs ?: return Decision.NOT_ARMED
        if (requireAndroidAuto && !androidAutoConnected) return Decision.NO_ANDROID_AUTO
        if (now - startedAt < minimumPlayingTimeMs) return Decision.NOT_ARMED
        if (suppressUntilPlaybackRestarts) return Decision.SUPPRESSED
        val previousResume = lastAutoResumeMs
        if (previousResume != null && now - previousResume <= manualPauseWindowMs) { suppressUntilPlaybackRestarts = true; return Decision.SUPPRESSED }
        if (requirePowerEvent) {
            val powerEvent = lastPowerEventMs ?: return Decision.NO_RECENT_POWER_EVENT
            if (now - powerEvent > powerEventWindowMs) return Decision.NO_RECENT_POWER_EVENT
        }
        pendingResumePauseMs = now
        return Decision.RESUME
    }

    /**
     * Final check made right before Play is sent. The car connection must still be on this phone:
     * Android Auto still connected (when required), the car audio output seen at pause time still
     * present, and no car-link loss signal since shortly before the pause.
     */
    fun confirmResume(
        enabled: Boolean,
        androidAutoConnected: Boolean,
        requireAndroidAuto: Boolean,
        carAudioOutputStillPresent: Boolean,
        carLinkLookbackMs: Long = DEFAULT_CAR_LINK_LOOKBACK_MS,
    ): Decision {
        val pausedAt = pendingResumePauseMs ?: return Decision.NOT_ARMED
        if (!enabled) { pendingResumePauseMs = null; return Decision.DISABLED }
        val moved = (requireAndroidAuto && !androidAutoConnected) ||
            !carAudioOutputStillPresent ||
            carLinkLostSince(pausedAt - carLinkLookbackMs)
        if (moved) {
            pendingResumePauseMs = null
            suppressUntilPlaybackRestarts = true
            return Decision.CAR_CONNECTION_MOVED
        }
        return Decision.RESUME
    }

    private fun carLinkLostSince(sinceMs: Long): Boolean = lastCarLinkLossMs?.let { it >= sinceMs } == true

    fun shouldResume(enabled: Boolean, androidAutoConnected: Boolean, requireAndroidAuto: Boolean, requirePowerEvent: Boolean, minimumPlayingTimeMs: Long = AppPreferences.DEFAULT_MINIMUM_PLAYING_TIME_MS, manualPauseWindowMs: Long = AppPreferences.DEFAULT_MANUAL_PAUSE_WINDOW_MS) = decide(enabled, androidAutoConnected, requireAndroidAuto, requirePowerEvent, minimumPlayingTimeMs, manualPauseWindowMs) == Decision.RESUME
    fun reset() { playingSinceMs = null; lastAutoResumeMs = null; lastPowerEventMs = null; suppressUntilPlaybackRestarts = false; pendingResumePauseMs = null; lastCarLinkLossMs = null; lastCarLinkSignal = null }

    companion object {
        /** A car-link loss this long before a pause still counts as "the car moved away". */
        const val DEFAULT_CAR_LINK_LOOKBACK_MS = 3_000L
        /** A car-link loss this soon after an automatic resume pauses playback again. */
        const val DEFAULT_UNDO_WINDOW_MS = 5_000L
        /** Minimum wait between the pause and Play, so slower car signals can arrive first. */
        const val MIN_CAR_LINK_SETTLE_MS = 1_500L
    }
}
