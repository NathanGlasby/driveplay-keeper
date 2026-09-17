package za.co.driveplaykeeper

import org.junit.Assert.assertEquals
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
        engine.onAutoResume()

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

    @Test
    fun manualPauseWindowStartsWhenPlayIsActuallySent() {
        engine.onPlaying()
        now = 5_000L
        assertTrue(engine.shouldResume(true, true, true, false, manualPauseWindowMs = 2_000L))

        // The configured resume can be delayed longer than the manual-pause window.
        now = 8_000L
        engine.onAutoResume()
        engine.onNotPlaying()
        engine.onPlaying()
        now = 10_000L

        assertFalse(engine.shouldResume(true, true, true, false, manualPauseWindowMs = 2_000L))
    }

    @Test
    fun disabledWinsEvenWhenPlaybackIsNotArmed() {
        assertEquals(
            ResumeDecisionEngine.Decision.DISABLED,
            engine.decide(
                enabled = false,
                androidAutoConnected = false,
                requireAndroidAuto = true,
                requirePowerEvent = false,
                minimumPlayingTimeMs = 4_000L,
                manualPauseWindowMs = 8_000L,
            ),
        )
    }

    @Test
    fun configurableArmAndManualPauseWindowsAreUsed() {
        engine.onPlaying()
        now = 2_000L
        assertTrue(engine.shouldResume(true, true, true, false, minimumPlayingTimeMs = 1_000L, manualPauseWindowMs = 2_000L))
        engine.onNotPlaying()
        engine.onPlaying()
        now = 5_000L
        assertTrue(engine.shouldResume(true, true, true, false, minimumPlayingTimeMs = 1_000L, manualPauseWindowMs = 2_000L))
    }
}
