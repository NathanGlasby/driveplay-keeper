package za.co.driveplaykeeper

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private lateinit var sessionManager: MediaSessionManager
    private lateinit var listenerComponent: ComponentName
    private var spotifyController: MediaController? = null
    private var androidAutoConnected = false
    private var carConnection: CarConnection? = null

    private val carConnectionObserver = Observer<Int> { connectionType ->
        androidAutoConnected = connectionType == CarConnection.CONNECTION_TYPE_PROJECTION
        publishStatus()
        if (!androidAutoConnected && preferences.requireAndroidAuto) {
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
        spotifyController?.unregisterCallback(playbackCallback)
        spotifyController = null
        decisionEngine.reset()
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
        unregisterReceiver(refreshReceiver)
        mainHandler.removeCallbacksAndMessages(null)
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
        val nextController = controllers.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
        if (spotifyController?.sessionToken == nextController?.sessionToken) {
            handlePlaybackState(nextController?.playbackState)
            return
        }

        spotifyController?.unregisterCallback(playbackCallback)
        spotifyController = nextController
        decisionEngine.reset()
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
                publishStatus(playback = getString(R.string.playback_playing))
            }

            PlaybackState.STATE_PAUSED -> {
                val shouldResume = decisionEngine.shouldResume(
                    enabled = preferences.enabled,
                    androidAutoConnected = androidAutoConnected,
                    requireAndroidAuto = preferences.requireAndroidAuto,
                    requirePowerEvent = preferences.requirePowerEvent,
                )
                decisionEngine.onNotPlaying()

                if (shouldResume) {
                    publishStatus(playback = getString(R.string.playback_resuming))
                    mainHandler.postDelayed({ resumeSpotifyOnce() }, preferences.resumeDelayMs)
                } else {
                    publishStatus(playback = getString(R.string.playback_paused))
                }
            }

            else -> {
                decisionEngine.onNotPlaying()
                publishStatus(playback = getString(R.string.playback_idle))
            }
        }
    }

    private fun resumeSpotifyOnce() {
        val controller = spotifyController ?: return
        if (!preferences.enabled) return
        if (preferences.requireAndroidAuto && !androidAutoConnected) return
        if (controller.playbackState?.state != PlaybackState.STATE_PAUSED) return

        controller.transportControls.play()
    }

    private fun publishStatus(playback: String? = null, error: String? = null) {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_ANDROID_AUTO_CONNECTED, androidAutoConnected)
            putExtra(EXTRA_SPOTIFY_SESSION, spotifyController != null)
            playback?.let { putExtra(EXTRA_PLAYBACK, it) }
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
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}
