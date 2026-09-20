# Signal Ahead MVP

Privacy-first Android utility that learns recurring weak-signal areas during journeys the user explicitly starts.

## Included

- Kotlin + Jetpack Compose UI
- Visible location foreground service with Stop action
- Defensive telephony/connectivity readings
- Room database for journeys, observations and zones
- Coarse recurrence-based weak-zone learning (possible after 2 journeys; confirmed after 3)
- Conservative approach warnings using distance and bearing
- Local delete control and honest limitation copy
- User-initiated CSV export through Android's document picker
- 30-day raw-observation cleanup when a journey starts
- Graceful missing/denied signal data handling

## Build

1. Open this folder in Android Studio Ladybug or newer.
2. Let Android Studio install Android SDK 35 and sync Gradle.
3. Run on a physical Android phone (telephony readings are not meaningful on most emulators).
4. Grant location. Notifications are optional but required for approach warnings on Android 13+.
5. Tap **Start Live Journey**.

To make a debug APK, use **Build > Build APK(s)**. The result is normally in `app/build/outputs/apk/debug/`.

## GitHub APK build

The included `.github/workflows/build-apk.yml` builds a debug APK on every push to `main` and on manual request. Open the repository's **Actions** tab, select **Build Android APK**, then download the `SignalAhead-debug-apk` artifact after the run succeeds.

## Important limitations

- This is a source MVP, not a Play-ready release.
- The current screen provides a zone list, not map tiles; this avoids requiring an API key and transmitting map requests.
- Database encryption, periodic cleanup outside journey start, tests and a privacy policy must be completed before public release.
- `READ_PHONE_STATE` may not be necessary on every supported device/API combination; verify the final signal fields and remove it if unused.
- Manufacturer behavior and Android throttling can delay readings. The app deliberately makes no guaranteed-warning claim.

## Suggested next engineering pass

Add tests around cell bucketing, recurrence across distinct journeys, confidence decay, warning cooldown, approximate location and retention. Then add a privacy-preserving offline map or an opt-in network-backed map.
