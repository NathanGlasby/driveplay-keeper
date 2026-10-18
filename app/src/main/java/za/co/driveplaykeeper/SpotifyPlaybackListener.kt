package za.co.driveplaykeeper

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer

class SpotifyPlaybackListener : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences by lazy { AppPreferences(this) }
    private val decisionEngine = ResumeDecisionEngine(clock = SystemClock::elapsedRealtime)
    private var lastDecision = ResumeDecisionEngine.Decision.NOT_ARMED

    private lateinit var sessionManager: MediaSessionManager
    private lateinit var listenerComponent: ComponentName
    private var spotifyController: MediaController? = null
    private var androidAutoConnected = false
    private var carConnection: CarConnection? = null
    private val resumeScheduler = ResumeScheduler(
        decisionEngine,
        object : ResumeScheduler.Poster {
            override fun postDelayed(callback: Runnable, delayMs: Long) {
                mainHandler.postDelayed(callback, delayMs)
            }

            override fun removeCallbacks(callback: Runnable) {
                mainHandler.removeCallbacks(callback)
            }
        },
    )
    private lateinit var audioManager: AudioManager
    private var carOutputIdsAtPause: Set<Int> = emptySet()

    private val carConnectionObserver = Observer<Int> { connectionType ->
        val wasAndroidAutoConnected = androidAutoConnected
        androidAutoConnected = connectionType == CarConnection.CONNECTION_TYPE_PROJECTION
        if (wasAndroidAutoConnected && !androidAutoConnected) {
            onCarLinkLost(ResumeDecisionEngine.CarLinkSignal.ANDROID_AUTO_DISCONNECTED)
        }
        if (wasAndroidAutoConnected &&
            !androidAutoConnected &&
            preferences.pauseOnAndroidAutoDisconnect
        ) {
            cancelPendingResume()
            decisionEngine.onAndroidAutoDisconnected()
            pauseSpotifyForAndroidAutoDisconnect()
        }
        publishStatus()
        if (!androidAutoConnected && preferences.requireAndroidAuto && !wasAndroidAutoConnected) {
            decisionEngine.reset()
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attachToSpotify(controllers.orEmpty())
        }

    private val playbackCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackState(state)
        }

        override fun onSessionDestroyed() {
            spotifyController = null
            decisionEngine.reset()
            lastDecision = ResumeDecisionEngine.Decision.NOT_ARMED
            refreshSessions()
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_POWER_CONNECTED ||
                intent?.action == Intent.ACTION_POWER_DISCONNECTED
            ) {
                decisionEngine.onPowerEvent()
                publishStatus()
            }
        }
    }

    // Signals that the car connection is moving away from this phone, for example when another
    // phone takes over the car connection. None of these need a runtime permission.
    private val carLinkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY ->
                    onCarLinkLost(ResumeDecisionEngine.CarLinkSignal.AUDIO_BECOMING_NOISY)
                UiModeManager.ACTION_EXIT_CAR_MODE ->
                    onCarLinkLost(ResumeDecisionEngine.CarLinkSignal.CAR_MODE_EXITED)
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (removedDevices.orEmpty().any(::isCarOutput)) {
                onCarLinkLost(ResumeDecisionEngine.CarLinkSignal.CAR_AUDIO_OUTPUT_REMOVED)
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REFRESH) {
                refreshSessions()
                publishStatus()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(MediaSessionManager::class.java)
        listenerComponent = ComponentName(this, SpotifyPlaybackListener::class.java)
        registerReceiver(
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
        )
        registerReceiver(
            carLinkReceiver,
            IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
            }
        )
        audioManager = getSystemService(AudioManager::class.java)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        val refreshFilter = IntentFilter(ACTION_REFRESH)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, refreshFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(refreshReceiver, refreshFilter)
        }

        carConnection = CarConnection(this).also { connection ->
            connection.type.observeForever(carConnectionObserver)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        preferences.recordListenerConnected()
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                listenerComponent,
                mainHandler,
            )
        } catch (_: SecurityException) {
            publishStatus(error = getString(R.string.status_access_required))
            return
        }
        refreshSessions()
    }

    override fun onListenerDisconnected() {
        preferences.recordListenerDisconnected()
        cancelPendingResume()
        spotifyController?.unregisterCallback(playbackCallback)
        spotifyController = null
        decisionEngine.reset()
        lastDecision = ResumeDecisionEngine.Decision.NOT_ARMED
        publishStatus(error = getString(R.string.status_listener_disconnected))
        requestRebind(listenerComponent)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        preferences.recordListenerDestroyed()
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {
            // The listener may already have been removed by the system.
        }
        spotifyController?.unregisterCallback(playbackCallback)
        carConnection?.type?.removeObserver(carConnectionObserver)
        unregisterReceiver(powerReceiver)
        unregisterReceiver(carLinkReceiver)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        unregisterReceiver(refreshReceiver)
        cancelPendingResume()
        super.onDestroy()
    }

    private fun refreshSessions() {
        try {
            attachToSpotify(sessionManager.getActiveSessions(listenerComponent))
        } catch (_: SecurityException) {
            publishStatus(error = getString(R.string.status_access_required))
        }
    }

    private fun attachToSpotify(controllers: List<MediaController>) {
        val nextController = controllers.firstOrNull { it.packageName == preferences.mediaPackage }
        if (spotifyController?.sessionToken == nextController?.sessionToken) {
            handlePlaybackState(nextController?.playbackState)
            return
        }

        spotifyController?.unregisterCallback(playbackCallback)
        cancelPendingResume()
        spotifyController = nextController
        decisionEngine.reset()
        lastDecision = ResumeDecisionEngine.Decision.NOT_ARMED
        nextController?.registerCallback(playbackCallback, mainHandler)
        handlePlaybackState(nextController?.playbackState)
        publishStatus()
    }

    private fun handlePlaybackState(state: PlaybackState?) {
        if (state == null) {
            publishStatus(playback = getString(R.string.playback_unavailable))
            return
        }

        when (state.state) {
            PlaybackState.STATE_PLAYING -> {
                decisionEngine.onPlaying()
                lastDecision = ResumeDecisionEngine.Decision.NOT_ARMED
                publishStatus(playback = getString(R.string.playback_playing))
            }

            PlaybackState.STATE_PAUSED -> {
                val decision = decisionEngine.decide(
                    enabled = preferences.enabled,
                    androidAutoConnected = androidAutoConnected,
                    requireAndroidAuto = preferences.requireAndroidAuto,
                    requirePowerEvent = preferences.requirePowerEvent,
                    minimumPlayingTimeMs = preferences.minimumPlayingTimeMs,
                    manualPauseWindowMs = preferences.manualPauseWindowMs,
                )
                lastDecision = decision
                preferences.recordDecision(describe(decision))
                val shouldResume = decision == ResumeDecisionEngine.Decision.RESUME
                decisionEngine.onNotPlaying()

                if (shouldResume) {
                    carOutputIdsAtPause = currentCarOutputIds()
                    publishStatus(playback = getString(R.string.playback_resuming))
                    scheduleResume(spotifyController)
                } else {
                    cancelPendingResume()
                    publishStatus(playback = getString(R.string.playback_paused))
                }
            }

            else -> {
                decisionEngine.onNotPlaying()
                publishStatus(playback = getString(R.string.playback_idle))
            }
        }
    }

    private fun scheduleResume(controller: MediaController?) {
        val session = controller?.let { mediaController ->
            val sessionToken = mediaController.sessionToken
            object : ResumeScheduler.Session {
                override fun isCurrent() = spotifyController?.sessionToken == sessionToken
                override fun isPaused() = mediaController.playbackState?.state == PlaybackState.STATE_PAUSED
                override fun play(): Boolean = try {
                    mediaController.transportControls.play()
                    true
                } catch (_: IllegalStateException) {
                    publishStatus(error = getString(R.string.status_session_expired))
                    refreshSessions()
                    false
                } catch (_: SecurityException) {
                    publishStatus(error = getString(R.string.status_access_required))
                    false
                }
            }
        }
        // Wait at least long enough for slower car signals (such as Android Auto's connection
        // state) to report that another phone has taken over the car connection.
        val delayMs = maxOf(preferences.resumeDelayMs, ResumeDecisionEngine.MIN_CAR_LINK_SETTLE_MS)
        resumeScheduler.schedule(
            session = session,
            delayMs = delayMs,
            conditions = {
                ResumeScheduler.Conditions(
                    enabled = preferences.enabled,
                    androidAutoConnected = androidAutoConnected,
                    requireAndroidAuto = preferences.requireAndroidAuto,
                    carAudioOutputStillPresent = currentCarOutputIds().containsAll(carOutputIdsAtPause),
                )
            },
            onSkipped = { decision ->
                if (decision == ResumeDecisionEngine.Decision.CAR_CONNECTION_MOVED) {
                    lastDecision = decision
                    preferences.recordDecision(describe(decision))
                    publishStatus(playback = getString(R.string.playback_car_connection_moved))
                }
            },
        )
    }

    private fun cancelPendingResume() = resumeScheduler.cancel()

    private fun onCarLinkLost(signal: ResumeDecisionEngine.CarLinkSignal) {
        when (decisionEngine.onCarLinkLost(signal)) {
            ResumeDecisionEngine.CarLinkLossAction.CANCEL_PENDING_RESUME -> {
                resumeScheduler.removeCallback()
                lastDecision = ResumeDecisionEngine.Decision.CAR_CONNECTION_MOVED
                preferences.recordDecision(describe(lastDecision))
                publishStatus(playback = getString(R.string.playback_car_connection_moved))
            }
            ResumeDecisionEngine.CarLinkLossAction.UNDO_AUTO_RESUME -> {
                lastDecision = ResumeDecisionEngine.Decision.CAR_CONNECTION_MOVED
                preferences.recordDecision(getString(R.string.decision_car_connection_moved_paused, signalLabel(signal)))
                pauseSpotifyAfterCarLinkLoss()
            }
            ResumeDecisionEngine.CarLinkLossAction.NONE -> Unit
        }
    }

    private fun pauseSpotifyAfterCarLinkLoss() {
        val controller = spotifyController ?: return
        if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) return
        try {
            controller.transportControls.pause()
        } catch (_: IllegalStateException) {
            refreshSessions()
            return
        } catch (_: SecurityException) {
            publishStatus(error = getString(R.string.status_access_required))
            return
        }
        publishStatus(playback = getString(R.string.playback_car_connection_moved))
    }

    private fun currentCarOutputIds(): Set<Int> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter(::isCarOutput)
            .map(AudioDeviceInfo::getId)
            .toSet()

    private fun isCarOutput(device: AudioDeviceInfo): Boolean =
        device.isSink && device.type in CAR_OUTPUT_TYPES

    private fun describe(decision: ResumeDecisionEngine.Decision): String =
        if (decision == ResumeDecisionEngine.Decision.CAR_CONNECTION_MOVED) {
            getString(
                R.string.decision_car_connection_moved,
                signalLabel(decisionEngine.lastCarLinkSignal),
            )
        } else {
            decision.name.lowercase().replace('_', ' ')
        }

    private fun signalLabel(signal: ResumeDecisionEngine.CarLinkSignal?): String = getString(
        when (signal) {
            ResumeDecisionEngine.CarLinkSignal.ANDROID_AUTO_DISCONNECTED -> R.string.signal_android_auto_disconnected
            ResumeDecisionEngine.CarLinkSignal.CAR_AUDIO_OUTPUT_REMOVED -> R.string.signal_car_audio_output_removed
            ResumeDecisionEngine.CarLinkSignal.AUDIO_BECOMING_NOISY -> R.string.signal_audio_becoming_noisy
            ResumeDecisionEngine.CarLinkSignal.CAR_MODE_EXITED -> R.string.signal_car_mode_exited
            null -> R.string.signal_car_audio_output_removed
        }
    )

    private fun pauseSpotifyForAndroidAutoDisconnect() {
        val controller = spotifyController ?: return
        if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) return

        try {
            controller.transportControls.pause()
        } catch (_: IllegalStateException) {
            refreshSessions()
            return
        } catch (_: SecurityException) {
            publishStatus(error = getString(R.string.status_access_required))
            return
        }
        publishStatus(playback = getString(R.string.playback_paused_after_android_auto_disconnect))
    }

    private fun publishStatus(playback: String? = null, error: String? = null) {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_ANDROID_AUTO_CONNECTED, androidAutoConnected)
            putExtra(EXTRA_SPOTIFY_SESSION, spotifyController != null)
            playback?.let { putExtra(EXTRA_PLAYBACK, it) }
            putExtra(EXTRA_DECISION, lastDecision.name)
            error?.let { putExtra(EXTRA_ERROR, it) }
        }
        sendBroadcast(intent)
    }

    companion object {
        const val ACTION_STATUS = "za.co.driveplaykeeper.STATUS"
        const val ACTION_REFRESH = "za.co.driveplaykeeper.REFRESH"
        const val EXTRA_ANDROID_AUTO_CONNECTED = "android_auto_connected"
        const val EXTRA_SPOTIFY_SESSION = "spotify_session"
        const val EXTRA_PLAYBACK = "playback"
        const val EXTRA_ERROR = "error"
        const val EXTRA_DECISION = "decision"

        // Output types a car head unit can appear as. Removal of one of these around a pause
        // means the car's audio path has left this phone.
        private val CAR_OUTPUT_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_USB_DEVICE)
            add(AudioDeviceInfo.TYPE_USB_ACCESSORY)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            add(AudioDeviceInfo.TYPE_BUS)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
    }
}
