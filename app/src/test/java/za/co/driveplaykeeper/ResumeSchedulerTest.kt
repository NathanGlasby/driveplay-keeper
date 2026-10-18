package za.co.driveplaykeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeSchedulerTest {
    private var now = 0L
    private val engine = ResumeDecisionEngine(clock = { now })
    private val posted = mutableListOf<Pair<Runnable, Long>>()
    private val removed = mutableListOf<Runnable>()
    private val scheduler = ResumeScheduler(
        engine,
        object : ResumeScheduler.Poster {
            override fun postDelayed(callback: Runnable, delayMs: Long) { posted += callback to delayMs }
            override fun removeCallbacks(callback: Runnable) { removed += callback; posted.removeAll { it.first === callback } }
        },
    )
    private val skipped = mutableListOf<ResumeDecisionEngine.Decision>()

    private inner class FakeSession(var paused: Boolean = true, var current: Boolean = true) : ResumeScheduler.Session {
        var playCount = 0
        override fun isCurrent() = current
        override fun isPaused() = paused
        override fun play(): Boolean { playCount++; paused = false; return true }
    }

    private fun conditions(androidAutoConnected: Boolean = true, carAudioOutputStillPresent: Boolean = true) =
        ResumeScheduler.Conditions(
            enabled = true,
            androidAutoConnected = androidAutoConnected,
            requireAndroidAuto = true,
            carAudioOutputStillPresent = carAudioOutputStillPresent,
        )

    /** Mirrors the listener: PLAYING long enough to arm, then a PAUSED state that decide() approves. */
    private fun ignitionPause(): ResumeDecisionEngine.Decision {
        engine.onPlaying()
        now += 5_000L
        val decision = engine.decide(true, true, true, false, 4_000L, 8_000L)
        engine.onNotPlaying()
        return decision
    }

    private fun runPosted() {
        val callbacks = posted.toList()
        posted.clear()
        callbacks.forEach { (callback, delayMs) -> now += delayMs; callback.run() }
    }

    private fun schedule(session: ResumeScheduler.Session?, conditions: ResumeScheduler.Conditions = conditions()) =
        scheduler.schedule(session, 1_500L, { conditions }, { skipped += it })

    @Test
    fun ignitionPauseIsScheduledConfirmedAndPlaySentOnce() {
        val session = FakeSession()
        assertEquals(ResumeDecisionEngine.Decision.RESUME, ignitionPause())

        assertTrue(schedule(session))
        assertEquals(1, posted.size)
        assertEquals(1_500L, posted.single().second)

        runPosted()
        assertEquals(1, session.playCount)
        assertTrue(skipped.isEmpty())
        assertFalse(scheduler.hasPendingResume)

        // Nothing left to fire, so Play is not sent a second time.
        runPosted()
        assertEquals(1, session.playCount)
    }

    @Test
    fun reschedulingReplacesOldCallbackWithoutDroppingNewPause() {
        val first = FakeSession()
        val second = FakeSession()
        assertEquals(ResumeDecisionEngine.Decision.RESUME, ignitionPause())
        schedule(first)
        assertEquals(ResumeDecisionEngine.Decision.RESUME, ignitionPause())
        schedule(second)

        assertEquals(1, removed.size)
        runPosted()
        assertEquals(0, first.playCount)
        assertEquals(1, second.playCount)
    }

    @Test
    fun missingSessionTokenCancelsPendingResume() {
        assertEquals(ResumeDecisionEngine.Decision.RESUME, ignitionPause())

        assertFalse(schedule(null))
        assertTrue(posted.isEmpty())
        assertFalse(scheduler.hasPendingResume)
        assertEquals(
            ResumeDecisionEngine.Decision.NOT_ARMED,
            engine.confirmResume(true, true, true, true),
        )
    }

    @Test
    fun androidAutoGoneWhenCallbackFiresSkipsPlay() {
        val session = FakeSession()
        assertEquals(ResumeDecisionEngine.Decision.RESUME, ignitionPause())
        schedule(session, conditions(androidAutoConnected = false))

        runPosted()
        assertEquals(0, session.playCount)
        assertEquals(listOf(ResumeDecisionEngine.Decision.CAR_CONNECTION_MOVED), skipped)
    }

    @Test
    fun carLinkLossWhilePendingRemovesCallback() {
        val session = FakeSession()
        assertEquals(ResumeDecisionEngine.Decision.RESUME, ignitionPause())
        schedule(session)

        assertEquals(
            ResumeDecisionEngine.CarLinkLossAction.CANCEL_PENDING_RESUME,
            engine.onCarLinkLost(ResumeDecisionEngine.CarLinkSignal.CAR_AUDIO_OUTPUT_REMOVED),
        )
        scheduler.removeCallback()

        runPosted()
        assertEquals(0, session.playCount)
    }

    @Test
    fun sessionNoLongerCurrentOrAlreadyPlayingDoesNotSendPlay() {
        val stale = FakeSession(current = false)
        assertEquals(ResumeDecisionEngine.Decision.RESUME, ignitionPause())
        schedule(stale)
        runPosted()
        assertEquals(0, stale.playCount)

        val alreadyPlaying = FakeSession(paused = false)
        assertEquals(ResumeDecisionEngine.Decision.RESUME, ignitionPause())
        schedule(alreadyPlaying)
        runPosted()
        assertEquals(0, alreadyPlaying.playCount)
    }
}
