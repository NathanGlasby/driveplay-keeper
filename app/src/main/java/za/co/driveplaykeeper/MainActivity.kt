package za.co.driveplaykeeper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var preferences: AppPreferences
    private lateinit var notificationAccessButton: Button
    private lateinit var notificationAccessGranted: TextView
    private lateinit var accessStatus: TextView
    private lateinit var carStatus: TextView
    private lateinit var spotifyStatus: TextView
    private lateinit var playbackStatus: TextView
    private var statusReceiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SpotifyPlaybackListener.ACTION_STATUS) return

            val connected = intent.getBooleanExtra(
                SpotifyPlaybackListener.EXTRA_ANDROID_AUTO_CONNECTED,
                false,
            )
            val spotifySession = intent.getBooleanExtra(
                SpotifyPlaybackListener.EXTRA_SPOTIFY_SESSION,
                false,
            )
            carStatus.text = if (connected) {
                getString(R.string.status_android_auto_connected)
            } else {
                getString(R.string.status_android_auto_disconnected)
            }
            spotifyStatus.text = if (spotifySession) {
                getString(R.string.status_spotify_found)
            } else {
                getString(R.string.status_spotify_not_found)
            }
            intent.getStringExtra(SpotifyPlaybackListener.EXTRA_PLAYBACK)?.let {
                playbackStatus.text = getString(R.string.status_playback_format, it)
            }
            intent.getStringExtra(SpotifyPlaybackListener.EXTRA_ERROR)?.let {
                playbackStatus.text = it
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        setContentView(buildContentView())
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        if (!statusReceiverRegistered) {
            val filter = IntentFilter(SpotifyPlaybackListener.ACTION_STATUS)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, filter)
            }
            statusReceiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessStatus()
        sendBroadcast(Intent(SpotifyPlaybackListener.ACTION_REFRESH).setPackage(packageName))
    }

    override fun onStop() {
        if (statusReceiverRegistered) {
            unregisterReceiver(statusReceiver)
            statusReceiverRegistered = false
        }
        super.onStop()
    }

    @Suppress("DEPRECATION")
    private fun buildContentView(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.app_subtitle)
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(24))
        })

        val enableSwitch = Switch(this).apply {
            text = getString(R.string.enable_protection)
            textSize = 18f
            setTextColor(Color.WHITE)
            isChecked = preferences.enabled
            setOnCheckedChangeListener { _, checked -> preferences.enabled = checked }
        }
        content.addView(enableSwitch)

        val requireCar = CheckBox(this).apply {
            text = getString(R.string.require_android_auto)
            setTextColor(Color.WHITE)
            isChecked = preferences.requireAndroidAuto
            setOnCheckedChangeListener { _, checked -> preferences.requireAndroidAuto = checked }
        }
        content.addView(requireCar)

        val powerOnly = CheckBox(this).apply {
            text = getString(R.string.power_event_only)
            setTextColor(Color.WHITE)
            isChecked = preferences.requirePowerEvent
            setOnCheckedChangeListener { _, checked -> preferences.requirePowerEvent = checked }
        }
        content.addView(powerOnly)

        content.addView(TextView(this).apply {
            text = getString(R.string.safety_explanation)
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(20))
        })

        notificationAccessButton = Button(this).apply {
            text = getString(R.string.open_notification_access)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        content.addView(notificationAccessButton)

        notificationAccessGranted = TextView(this).apply {
            text = getString(R.string.notification_access_granted)
            textSize = 16f
            setTextColor(Color.rgb(30, 215, 96))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
        }
        content.addView(notificationAccessGranted)

        content.addView(Space(this), LinearLayout.LayoutParams(1, dp(24)))
        content.addView(sectionTitle(getString(R.string.live_status)))

        accessStatus = statusLine(getString(R.string.status_checking_access))
        carStatus = statusLine(getString(R.string.status_android_auto_unknown))
        spotifyStatus = statusLine(getString(R.string.status_spotify_not_found))
        playbackStatus = statusLine(getString(R.string.status_playback_format, getString(R.string.playback_unavailable)))
        content.addView(accessStatus)
        content.addView(carStatus)
        content.addView(spotifyStatus)
        content.addView(playbackStatus)

        content.addView(Space(this), LinearLayout.LayoutParams(1, dp(24)))
        content.addView(sectionTitle(getString(R.string.how_it_works_title)))
        content.addView(TextView(this).apply {
            text = getString(R.string.how_it_works)
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setLineSpacing(0f, 1.15f)
        })

        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 22, 27))
            addView(content)
        }
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 19f
        setTextColor(Color.rgb(30, 215, 96))
        setPadding(0, 0, 0, 10)
    }

    private fun statusLine(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(Color.WHITE)
        setPadding(0, 5, 0, 5)
    }

    private fun updateAccessStatus() {
        val manager = getSystemService(NotificationManager::class.java)
        val component = ComponentName(this, SpotifyPlaybackListener::class.java)
        val accessGranted = manager.isNotificationListenerAccessGranted(component)

        notificationAccessButton.visibility = if (accessGranted) View.GONE else View.VISIBLE
        notificationAccessGranted.visibility = if (accessGranted) View.VISIBLE else View.GONE
        accessStatus.text = if (accessGranted) {
            getString(R.string.status_access_granted)
        } else {
            getString(R.string.status_access_required)
        }
    }

}
