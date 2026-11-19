# Bootloader Studio

An unofficial Android client for Xiaomi bootloader unlocking. It talks to Xiaomi's
unlock servers using the same server-authorized procedure as the official Windows
Mi Unlock tool and stages the signed unlock token over USB fastboot.

> **Not affiliated with Xiaomi.** Xiaomi, Redmi, POCO, MIUI, HyperOS, and Mi Unlock
> are trademarks of Xiaomi Inc. This project is not endorsed by or supported by Xiaomi.

## What it does

- Signs in to a Xiaomi account through Xiaomi's own login page in a WebView.
- Resolves the account region and the matching unlock server, aggregating the
  session cookies returned across the login redirect chain.
- Reads the fastboot product codename, lock state, and device token.
- Requests a nonce and an eligibility decision from Xiaomi's server.
- Shows Xiaomi server errors with localized official wording and actionable
  guidance, while always preserving the raw server message.
- Sends the signed `ahaUnlock` request and stages the returned token with
  `download:<size>` followed by `oem unlock`.
- Re-reads the fastboot lock state after `oem unlock` and reports whether the
  unlock was verified, unverifiable because the device rebooted, or still locked.
- Stores the Xiaomi session as an Android Keystore AES/GCM encrypted blob and
  migrates plaintext sessions written by older builds.

## What it does not do

- It does not bypass waiting periods, quotas, region checks, Community levels,
  account binding, or any other Xiaomi eligibility rule. The Xiaomi server makes
  the decision and its error details are always preserved.
- It does not retry rejected unlock requests automatically.
- It does not include Xiaomi binaries, ROMs, or proprietary tools.

## Requirements

- Android 9 (API 28) or newer on the **host** device.
- USB host / OTG support on the host device.
- A second Android device is required: the Xiaomi phone being unlocked must be in
  fastboot mode, so it cannot run the app itself.
- An eligible Xiaomi account bound to the target device.

## Build

```bash
sh gradlew assembleDebug
```

`./gradlew` works on a normal checkout. Use `sh gradlew` when the project is on
shared storage mounted without execute permission (for example
`/storage/emulated/0` on Android).

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Run the unit tests with:

```bash
sh gradlew testDebugUnitTest
```

The test suite covers the request signing and encryption scheme, fastboot packet
parsing and value normalization, hex utilities, the localized official Xiaomi
error-code mapping, session encoding/migration, unlock result verification, preflight
cooldown, the ViewModel state machine, cookie aggregation across redirects, and
the auth/unlock repositories against MockWebServer.

The build expects an Android SDK with API 36 and build-tools 36.1.0. Set
`sdk.dir` in `local.properties` or export `ANDROID_HOME`.

### Release builds

Release builds are signed and produce one APK per ABI plus a universal APK:

```bash
sh gradlew assembleRelease
```

| APK | ABI |
| --- | --- |
| `app-armeabi-v7a-release.apk` | 32-bit ARM |
| `app-arm64-v8a-release.apk` | 64-bit ARM |
| `app-x86-release.apk` | 32-bit x86 |
| `app-x86_64-release.apk` | 64-bit x86 |
| `app-universal-release.apk` | all of the above |

Signing reads `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` from the environment or
`local.properties`. The keystore lives in `keystore/` and is never committed.

CI (`.github/workflows/release.yml`) builds the same signed release APKs on
every push to `main`, uploads them as workflow artifacts, and attaches them to a
GitHub Release when a `v*` tag is pushed. CI receives the keystore and passwords
through the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and
`KEY_PASSWORD` repository secrets.

### Linux ARM64 hosts

Google's Maven `aapt2` artifact is x86_64-only. On an aarch64 Linux host, install
an aarch64 build of aapt2 (for example build-tools 36.1.0 from
[android-arm-build-tools](https://github.com/Commit451/android-arm-build-tools),
MIT licensed) and point Gradle at it:

```properties
# ~/.gradle/gradle.properties
android.aapt2FromMavenOverride=/absolute/path/to/aapt2
```

## Signing keys and client versions

The unlock request format includes an HMAC key and a Windows client version
string. These values come from the unofficial reference implementation and can
change when Xiaomi updates its client. If Xiaomi starts rejecting requests with a
client-version error, those constants need to be updated.

## Login architecture & troubleshooting

The login architecture is cleanly separated into two distinct components:

1. **`LoginScreen` (Native Material 3)**:
   - Does not embed a WebView directly into the app's login view.
   - Designed following Google's Material Design 3 guidelines: clear hierarchy, hero container, active session indicator, security explanations (HttpOnly tokens, direct official server communication), fastboot unlock requirements, and official disclaimer.
   - Acts as the portal to launch web authentication or continue with an existing session.

2. **`AppWebViewScreen` (100% Jetpack Compose Reusable WebView)**:
   - A standalone, reusable Compose WebView screen designed for any web flow in the app.
   - Built identically to the proven reference `MiTools` behavior to eliminate the root cause of blank white screens on Xiaomi devices while maintaining 100% Jetpack Compose architecture:
     - Uses default view layer type (no forced offscreen hardware layer overrides) to ensure seamless frame compositing inside Compose on MIUI/HyperOS.
     - Uses `userAgent = "(Android) Mobile"`. Decompilation of Xiaomi's frontend bundle showed that desktop Linux user agents trigger chunk `desk.07304c34.chunk.js`, which does not contain the `/login` routes and causes React Router to render `null` (blank white screen). In contrast, `"(Android) Mobile"` loads chunk `mobile.7172b162.chunk.js`, which contains `/login/password`, `/login/phone`, and all mobile identity validation routes.
     - Pure mobile responsive rendering without artificial desktop viewport meta manipulation.
     - Disables unnecessary CSS transitions via `onPageFinished` style injection for snappy performance.
     - Detects Xiaomi STS authorization completion via `{"R":"","S":"OK"}` end-pattern detection and extracts `passToken`, `deviceId`, and `userId` cookies immediately.
     - Lifecycle-aware bindings (`onResume`, `onPause`, `onDestroy`).
     - Material 3 Scaffold with TopAppBar, URL host indicator, reload button, dropdown menu (Clear cache & cookies, Open in external browser, Copy link), clean top `LinearProgressIndicator` in Xiaomi Orange, and success overlay card.
     - Explicit Material 3 error states for network/connection failures with retry and browser fallback options.

Error codes returned by Xiaomi are mapped to the wording from the official Mi Unlock 6.5 client (`miflash_unlock-en-6.5.224.28`). Unknown codes keep Xiaomi's server message and the numeric code so nothing is hidden.

## Session storage and unlock verification

- The Xiaomi session (pass token, device ID, account ID, resolved region/host,
  ssecurity, service token, and resolution time) is persisted as a single
  AES/GCM blob. The key is generated in and never leaves the Android Keystore.
  A plaintext session written by older builds is migrated on first launch and
  the legacy keys are removed.
- After `oem unlock` returns `OKAY`, the app re-reads `getvar unlocked`. A
  confirmed unlock or a device that rebooted/disconnected is reported as a
  successful request; a device that still reports `locked` is shown as an error
  with on-device confirmation guidance.
- A successful eligibility preflight (nonce + `clearDevice` notice) is reused
  for the same device for three minutes. Errors and device changes force a
  fresh request, so a failed preflight can always be retried immediately.
- The device screen refreshes on USB attach/detach and through an explicit
  refresh button. Before requesting approval and again before staging the
  token, the app checks the USB handle, product codename, device token, and
  session device ID. A changed handle or token stops the flow instead of
  unlocking the wrong device.
- Fastboot reads use a 5-second default timeout. Transport failures are
  classified as disconnected, timed out, or other, and the UI shows a localized
  message with a slow-read hint instead of a raw English exception.

## Risk

Unlocking the bootloader usually erases all user data and may void the warranty.
Use this app only on devices you own and at your own risk.

## Attribution

The unlock signing and USB fastboot flow were implemented with reference to the
Apache-2.0 licensed [MiTools](https://github.com/MiForge/MiTools) project.

The small set of vector icons used in the app comes from Google's
[Material Icons](https://github.com/google/material-design-icons) project,
licensed under Apache-2.0.
