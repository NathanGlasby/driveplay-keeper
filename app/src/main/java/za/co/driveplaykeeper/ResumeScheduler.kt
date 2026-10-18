package za.co.driveplaykeeper

/**
 * Owns the single delayed resume callback. Kept free of Android types so the listener's
 * schedule -> confirm -> Play path can be unit tested on the JVM.
 */
class ResumeScheduler(
    private val engine: ResumeDecisionEngine,
    private val poster: Poster,
) {
    interface Poster {
        fun postDelayed(callback: Runnable, delayMs: Long)
        fun removeCallbacks(callback: Runnable)
    }

    /** The media session a resume was scheduled for. */
    interface Session {
        /** False once the listener has moved to a different session. */
        fun isCurrent(): Boolean
        fun isPaused(): Boolean
        /** Sends Play; returns false if it could not be sent. */
        fun play(): Boolean
    }

    data class Conditions(
        val enabled: Boolean,
        val androidAutoConnected: Boolean,
        val requireAndroidAuto: Boolean,
        val carAudioOutputStillPresent: Boolean,
    )

    private var pending: Runnable? = null

    val hasPendingResume: Boolean get() = pending != null

    /**
     * Schedules Play for a pause that [ResumeDecisionEngine.decide] just approved. Replacing an
     * older callback does not clear the newly approved pause. Returns false (and tells the engine
     * the resume was cancelled) when there is no session to resume.
     */
    fun schedule(
        session: Session?,
        delayMs: Long,
        conditions: () -> Conditions,
        onSkipped: (ResumeDecisionEngine.Decision) -> Unit,
    ): Boolean {
        removeCallback()
        if (session == null) {
            engine.onPendingResumeCancelled()
            return false
        }
        val callback = Runnable {
            pending = null
            fire(session, conditions(), onSkipped)
        }
        pending = callback
        poster.postDelayed(callback, delayMs)
        return true
    }

    /** Cancels any pending resume, including the engine's record of the approved pause. */
    fun cancel() {
        removeCallback()
        engine.onPendingResumeCancelled()
    }

    /** Removes the delayed callback only, for when the engine has already dropped the pause. */
    fun removeCallback() {
        pending?.let(poster::removeCallbacks)
        pending = null
    }

    private fun fire(
        session: Session,
        conditions: Conditions,
        onSkipped: (ResumeDecisionEngine.Decision) -> Unit,
    ) {
        if (!session.isCurrent()) {
            engine.onPendingResumeCancelled()
            return
        }
        val decision = engine.confirmResume(
            enabled = conditions.enabled,
            androidAutoConnected = conditions.androidAutoConnected,
            requireAndroidAuto = conditions.requireAndroidAuto,
            carAudioOutputStillPresent = conditions.carAudioOutputStillPresent,
        )
        if (decision != ResumeDecisionEngine.Decision.RESUME) {
            onSkipped(decision)
            return
        }
        if (!session.isPaused() || !session.play()) {
            engine.onPendingResumeCancelled()
            return
        }
        engine.onAutoResume()
    }
}
