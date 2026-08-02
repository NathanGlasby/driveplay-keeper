package za.co.driveplaykeeper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeDecisionEngineTest {
    private var now = 0L
    private val engine = ResumeDecisionEngine(clock = { now })

    @Test
    fun resumesFirstPauseAfterStablePlayback() {
        engine.onPlaying()
        now = 5_000L

        assertTrue(
            engine.shouldResume(
                enabled = true,
                androidAutoConnected = true,
                requireAndroidAuto = true,
                requirePowerEvent = false,
            )
        )
    }

    @Test
    fun respectsSecondPauseInsideManualWindow() {
        engine.onPlaying()
        now = 5_000L
        assertTrue(engine.shouldResume(true, true, true, false))

        engine.onNotPlaying()
        engine.onPlaying()
        now = 10_000L
        assertFalse(engine.shouldResume(true, true, true, false))
    }

    @Test
    fun doesNothingOutsideAndroidAuto() {
        engine.onPlaying()
        now = 5_000L

        assertFalse(engine.shouldResume(true, false, true, false))
    }

    @Test
    fun powerOnlyModeRequiresRecentPowerEvent() {
        engine.onPlaying()
        now = 5_000L
        assertFalse(engine.shouldResume(true, true, true, true))

        engine.onPowerEvent()
        assertTrue(engine.shouldResume(true, true, true, true))
    }

    @Test
    fun reconnectPlaybackRearmsGuardButDisconnectDoesNotResumeIt() {
        engine.onPlaying()
        now = 5_000L
        engine.onAndroidAutoDisconnected()

        // A delayed callback or PAUSED state after disconnect remains suppressed.
        assertFalse(engine.shouldResume(true, true, true, false))

        // If Android Auto or Spotify starts playback again after reconnect, that
        // fresh PLAYING state is a valid new session and arms later protection.
        engine.onPlaying()
        now = 10_000L
        assertTrue(engine.shouldResume(true, true, true, false))
    }
}
