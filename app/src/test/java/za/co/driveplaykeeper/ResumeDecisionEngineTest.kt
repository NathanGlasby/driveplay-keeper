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

    private fun armAndPause(): ResumeDecisionEngine.Decision {
        engine.onPlaying()
        now += 5_000L
        val decision = engine.decide(true, true, true, false, 4_000L, 8_000L)
        engine.onNotPlaying()
        return decision
    }

    private fun confirm(androidAutoConnected: Boolean = true, carAudioOutputStillPresent: Boolean = true) =
        engine.confirmResume(
            enabled = true,
            androidAutoConnected = androidAutoConnected,
            requireAndroidAuto = true,
            carAudioOutputStillPresent = carAudioOutputStillPresent,
        )

    @Test
    fun carIgnitionPauseStillResumesWhenCarConnectionStays() {
        assertEquals(ResumeDecisionEngine.Decision.RESUME, armAndPause())
        now += 1_500L
        assertEquals(ResumeDecisionEngine.Decision.RESUME, confirm())
    }

    @Test
    fun audioBecomingNoisyJustBeforePauseSkipsResume() {
        engine.onPlaying()
        now = 5_000L
        engine.onCarLinkLost(ResumeDecisionEngine.CarLinkSignal.AUDIO_BECOMING_NOISY)
        now = 5_200L

        assertEquals(
            ResumeDecisionEngine.Decision.CAR_CONNECTION_MOVED,
            engine.decide(true, true, true, false, 4_000L, 8_000L),
        )
    }

    @Test
    fun carLinkLossLongBeforePauseDoesNotBlockLaterResume() {
        engine.onPlaying()
        now = 1_000L
        engine.onCarLinkLost(ResumeDecisionEngine.CarLinkSignal.CAR_AUDIO_OUTPUT_REMOVED)
        // Playback keeps going and re-arms; the old loss is outside the look-back window.
        engine.onPlaying()
        now = 10_000L

        assertEquals(
            ResumeDecisionEngine.Decision.RESUME,
            engine.decide(true, true, true, false, 4_000L, 8_000L),
        )
    }

    @Test
    fun carAudioOutputRemovedWhileResumeIsPendingCancelsIt() {
        assertEquals(ResumeDecisionEngine.Decision.RESUME, armAndPause())
        now += 400L

        assertEquals(
            ResumeDecisionEngine.CarLinkLossAction.CANCEL_PENDING_RESUME,
            engine.onCarLinkLost(ResumeDecisionEngine.CarLinkSignal.CAR_AUDIO_OUTPUT_REMOVED),
        )
        now += 1_100L
        assertEquals(ResumeDecisionEngine.Decision.NOT_ARMED, confirm())
    }

    @Test
    fun androidAutoGoneAtConfirmTimeSkipsResume() {
        assertEquals(ResumeDecisionEngine.Decision.RESUME, armAndPause())
        now += 1_500L

        assertEquals(ResumeDecisionEngine.Decision.CAR_CONNECTION_MOVED, confirm(androidAutoConnected = false))
    }

    @Test
    fun missingCarAudioOutputAtConfirmTimeSkipsResume() {
        assertEquals(ResumeDecisionEngine.Decision.RESUME, armAndPause())
        now += 1_500L

        assertEquals(ResumeDecisionEngine.Decision.CAR_CONNECTION_MOVED, confirm(carAudioOutputStillPresent = false))
    }

    @Test
    fun carLinkLossShortlyAfterAutoResumeAsksToPauseAgain() {
        assertEquals(ResumeDecisionEngine.Decision.RESUME, armAndPause())
        now += 1_500L
        assertEquals(ResumeDecisionEngine.Decision.RESUME, confirm())
        engine.onAutoResume()
        now += 2_000L

        assertEquals(
            ResumeDecisionEngine.CarLinkLossAction.UNDO_AUTO_RESUME,
            engine.onCarLinkLost(ResumeDecisionEngine.CarLinkSignal.ANDROID_AUTO_DISCONNECTED),
        )
    }

    @Test
    fun carLinkLossLongAfterAutoResumeDoesNothing() {
        assertEquals(ResumeDecisionEngine.Decision.RESUME, armAndPause())
        engine.onAutoResume()
        now += 30_000L

        assertEquals(
            ResumeDecisionEngine.CarLinkLossAction.NONE,
            engine.onCarLinkLost(ResumeDecisionEngine.CarLinkSignal.CAR_MODE_EXITED),
        )
    }

    @Test
    fun pauseRightAfterCarLinkLossStaysSkippedUntilPlaybackRestarts() {
        assertEquals(ResumeDecisionEngine.Decision.RESUME, armAndPause())
        engine.onCarLinkLost(ResumeDecisionEngine.CarLinkSignal.AUDIO_BECOMING_NOISY)
        now += 500L
        assertEquals(
            ResumeDecisionEngine.Decision.CAR_CONNECTION_MOVED,
            engine.decide(true, true, true, false, 4_000L, 8_000L),
        )

        // A fresh, stable playback session later on arms protection again.
        now += 10_000L
        engine.onPlaying()
        now += 5_000L
        assertEquals(
            ResumeDecisionEngine.Decision.RESUME,
            engine.decide(true, true, true, false, 4_000L, 8_000L),
        )
    }

    @Test
    fun intentionalSecondPauseIsStillRespected() {
        assertEquals(ResumeDecisionEngine.Decision.RESUME, armAndPause())
        now += 1_500L
        assertEquals(ResumeDecisionEngine.Decision.RESUME, confirm())
        engine.onAutoResume()
        engine.onPlaying()
        now += 3_000L

        assertEquals(
            ResumeDecisionEngine.Decision.SUPPRESSED,
            engine.decide(true, true, true, false, 0L, 8_000L),
        )
    }
}
