# DrivePlay Keeper

My car has an annoying habit. Spotify keeps playing when I turn the engine off
and leave the key in accessory mode, but it pauses as soon as I start the engine
again. Android Auto never disconnects. The music just stops.

DrivePlay Keeper is the workaround I made for it. The app watches Spotify's
Android media session and sends Play once after the unwanted pause.

## How it behaves

Spotify must be playing for at least four seconds before protection is armed.
While Android Auto is connected, the next Pause is treated as the car's ignition
pause and playback resumes after 900 milliseconds.

An intentional pause still works. Press Pause again within eight seconds and the
app leaves it alone. DrivePlay Keeper only reacts to a paused session, so a
stopped session or the end of a playlist stays stopped.

## Install it

DrivePlay Keeper requires Android 9 or newer.

1. Download the APK from the [latest release](../../releases/latest).
2. Allow your browser or file manager to install unknown apps when Android asks.
3. Install and open DrivePlay Keeper.
4. Tap **Grant notification access** and enable **DrivePlay Keeper media monitor**.
5. Leave **Only act while Android Auto is connected** enabled.
6. Start Spotify through Android Auto and let it play for at least four seconds.

The live status section shows whether the app can see Android Auto and Spotify.

## Why it needs notification access

Android only lets approved notification listeners control another app's active
media session. DrivePlay Keeper uses that access to find the session belonging
to `com.spotify.music` and send Play.

The app does not read message content, collect data, or make network requests.
There are no analytics or accounts.

## Limits

Android Auto reports whether it is connected, but it does not expose the
position of the ignition key. If the car's pause looks identical to a button
press, DrivePlay Keeper may resume after the first intentional pause. Pressing
Pause a second time within eight seconds overrides it.

The optional power-change setting is stricter. It only permits a resume after
the phone starts or stops charging, which helps in cars that briefly interrupt
USB power during startup. Some cars do not expose that change, so the setting is
off by default.

## Build it

Run the checks and create the APK on Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug packageDebugApk
```

Gradle puts the finished APK in `artifacts/`. Android build intermediates go to
the system temporary directory to avoid OneDrive file-locking problems.

## License

DrivePlay Keeper is available under the [MIT License](LICENSE).
