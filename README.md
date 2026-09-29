# Stop Apps

An Android app that force-stops running apps **without root, Shizuku, or ADB** — using only the Android accessibility service to automate the same *App info → Force stop → OK* screens you could tap by hand.

## Features

- **Running apps list** (default tab) with app names, icons, best-effort live status and per-app RAM. "Running" = apps seen in the usage-events stream since your last stop run (30 min window before the first run, capped at 6 h)
- **Total / used / free RAM** with a visual meter (system-wide values are exact)
- **Select individual apps or Select all** (running-only filter included)
- **User whitelist** — tap the shield on any app to always skip it; manage it on the Whitelist screen
- **Automatic safety whitelist** — 54 system-critical packages (System UI, Settings, dialer, SMS, package installer, OEM managers…) plus your launcher, keyboard and this app itself are never offered for stopping
- **Turbo mode** — no pause between apps (vs 1000 ms normally) and 50 ms confirmation tap (vs 100 ms)
- **Live run log** — every step timestamped: tapped Force stop, confirmed, stopped / failed / skipped with reasons
- **Progress notification** with cancel action and a summary when the run finishes

## How it works

For each selected app the app:

1. Opens `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the package
2. The accessibility service watches the Settings / OEM security-center windows (window-state-changed + view-scrolled events, like the reference implementation) and clicks the **Force stop** button — matched by the *localized* Settings strings for `force_stop` / `menu_item_force_stop`, resolved from the Settings package itself, with English and view-id fallbacks
3. Enables content-change events, then clicks **OK** (`com.android.settings:id/button1` / `android:id/button1`, with localized system OK/Yes string fallbacks) in the confirmation dialog
4. Verifies the OS `FLAG_STOPPED` flag — success is judged by the OS, not the screen

The confirmation stage is armed *before* the Force stop tap, so a fast dialog can never be missed. Each package gets an 8-second watchdog and one reopen-and-retry; a disabled Force stop on MIUI means "already stopped" and is skipped. `com.android.chrome` is always stopped last. Aggressive accessibility monitoring is enabled **only while a run is active**. The run refuses to start if the accessibility service is enabled but not yet connected. One failure never aborts the queue.

## Requirements

- Android 8.0 (API 26)+
- **Usage access** permission (to detect running apps) — the app guides you to grant it
- **Accessibility service** enabled for "Stop Apps automation" — the app guides you to enable it
- Screen must be on during a run (the automation taps real UI)

## Build

```bash
# Install Android SDK platform + build-tools, then:
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17, Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

Run the unit tests:

```bash
./gradlew testDebugUnitTest
```

Covers the pure-JVM logic: force-stop/confirm button matchers, the automatic whitelist, automation timing policy (turbo vs normal), event-type toggling, queue ordering (Chrome last), running-app classification windows, and the retry state machine (40 tests).

## Testing status

- ✅ Release build assembles cleanly (`assembleDebug`)
- ✅ Unit tests pass (button matchers, whitelist)
- ⚠️ **Not yet validated on a physical device.** The accessibility automation drives real OEM Settings apps, which differ between manufacturers (Samsung, Xiaomi, Pixel, …). Install the APK on a real phone, enable the accessibility service, and run a small batch first.

## Limitations (honest)

- Stock Android doesn't expose a fully reliable list of background processes to ordinary apps; "running" means "seen in usage events recently" and per-app RAM is best-effort (ActivityManager exposes little on modern Android, so many entries show no RAM figure).
- The app list shows launchable apps (queried via a declared launcher intent, so no `QUERY_ALL_PACKAGES` permission is needed); apps without a launcher icon aren't listed.
- An app can only be stopped if the OEM's App info page exposes an enabled Force stop button.
- The automation needs the screen on and takes ~1s per app (faster in Turbo mode).

## License

MIT — see [LICENSE](LICENSE).
