# Velagram for Android TV

Native Android TV adaptation of the local `myko-ios` / Velagram app, using its logo, bundled channel directory, graphite design, catalog services and private service configuration. Android 8.0 / API 26 or newer. Package: `com.velagram.tv`.

**Version 1.3 adds verified in-app GitHub updates and rebuilds the TV browsing and playback experience with cinematic artwork, cast filmographies, automatic unified source discovery and custom controls in both players. A signed APK is ready for sideloading. Full real-account/physical-TV parity is not certified.** See [the redesign review](docs/REDESIGN_REVIEW.md) and read [feature status](docs/FEATURE_STATUS.md) and [validation](docs/VALIDATION.md) for the boundaries.

## Install the APK

Download the universal APK from [GitHub Releases](https://github.com/rtk19/velagram-tv/releases), or build `app/build/outputs/apk/release/app-release.apk` locally. It includes ARM64, ARMv7, x86 and x86_64 native libraries; it is approximately 130 MB because it includes TDLib and VideoLAN. The same local RSA signing key signs Debug and Release builds, allowing upgrades in place. Preserve `config/velagram-tv.jks` to keep that upgrade identity.

After enabling developer options and network/wireless debugging on a TV:

```sh
# Pair only if the TV requires it; use the pairing port/code from its screen.
.tools/android-sdk/platform-tools/adb pair TV_IP:PAIRING_PORT PAIRING_CODE

# Install and launch on one or several already authorized TVs.
./scripts/install-tv.sh TV_IP:ADB_PORT
./scripts/install-tv.sh FIRST_TV:5555 SECOND_TV:5555

# Or install on every currently connected, authorized device.
./scripts/install-tv.sh
```

Physical-TV installation was deferred at the owner's request. The app has been installed and exercised on an Android TV API 34 ARM64 emulator.

## Build and Android Studio

Open this folder in Android Studio. The wrapper pins Gradle 8.11.1 and verifies its distribution checksum. AGP 8.9.1, compile/target SDK 35, build tools 36.0.0, and Java 17 language level are pinned. Use JDK 17+; Android Studio's bundled JDK also works.

On this computer the project-local SDK, Gradle cache, signing key, service configuration and TV emulator are already prepared:

```sh
./scripts/build.sh assembleRelease
./scripts/build.sh testDebugUnitTest lintRelease
./scripts/start-emulator.sh            # Apple Silicon TV emulator
APK="$PWD/app/build/outputs/apk/release/app-release.apk" ./scripts/install-tv.sh
```

For a fresh checkout:

```sh
# Prefer a licensed installed Android SDK with platform 35 and build tools 36.
export ANDROID_HOME=/path/to/android-sdk
./scripts/setup.sh
# Optional: copy service credentials from the iOS checkout, reading it only.
./scripts/setup.sh /path/to/myko-ios
./scripts/build.sh assembleRelease
```

`setup.sh` can download the SDK platform/build tools into `.tools/` on macOS if needed, reusing the licenses already accepted in Android Studio. `start-emulator.sh` can download the ARM64 TV system image into this project. The installed emulator executable is reused. Source files in the iOS project are never modified.

## Service configuration

The owner's TMDB token, SubDL key and Telegram API ID/hash were copied into ignored `config/local.properties`. They are compiled into this private APK. No credentials are checked into the project sources. Safe defaults are in `config/local.properties.example`.

Settings can override the TMDB and SubDL credentials on the TV. Telegram requires a new phone/code/2FA/email or QR login on the TV; iOS sessions are not transferable. User-entered secrets, server tokens and the Telegram database encryption key use Android Keystore AES-GCM storage. Account sessions and server tokens are excluded from JSON exports.

Add IPTV/Xtream, Jellyfin/Emby and an optional Stremio-compatible HTTP add-on using More or Settings. Account-dependent features require the corresponding account. The client does not send chat messages or automatically join Telegram groups.

## Remote controls

Use D-pad and Select throughout the interface; Back returns to the preceding section. Hold Select on a media/channel card for favorites, hide/show, ordering, downloading and removal. Hardware media Play/Pause, Fast Forward and Rewind work in both players. The primary player provides speed, audio/subtitle tracks, fit/zoom/fill, repeat, PiP and a VLC fallback. Menu opens subtitle tools. Background audio is optional in Settings and uses a Media3 foreground media service in the primary player.

More includes direct URLs, local media/document import, playlists, servers, profiles, service/playback settings, backup/restore, storage and notices. Some TVs need a document-provider/file-manager app to use local file pickers; network URLs do not require one.

## Data and backups

Libraries, resume positions and live-TV preferences are stored per profile. Telegram login, connected-server credentials and service settings are device-wide. Versioned Android JSON backups preserve profiles and preference/library metadata, not media bytes or Telegram sessions. They are not interchangeable with iOS JSON backups. Cache limits/retention apply to temporary app cache; Telegram saved files are managed separately.

## Dependencies and attribution

The Vela/Velagram foundation retains Apache 2.0 attribution in [LICENSE](LICENSE) and [NOTICE](NOTICE). AndroidX/Media3, OkHttp, ZXing, Glide, Kotlin/desugaring and community-packaged TDLib retain their own licenses. VideoLAN/libVLC 3.6.5 is LGPL 2.1 or later and dynamically linked. [Third-party notices](docs/THIRD_PARTY.md) include upstream source references. The app is independent of Telegram, TMDB, VideoLAN, Google and the channel providers.

## Updates and publishing

The app checks the latest stable public GitHub release when opened, at most once every six hours, and offers **Download** or **Later**. Settings → General → **Check for updates** always checks immediately. Download progress and cancellation are available. After verifying SHA-256, package name, signing certificate, minimum Android version and increasing versionCode, Velagram offers installation through Android's installer. Android may first ask you to allow installations from Velagram. Installation always requires your confirmation.

A completed download survives app restarts; Check for updates offers installation again. Interrupted downloads can be retried from Settings. Checks require internet access; failures during automatic checks stay quiet. No background service runs while the app is closed. Drafts and prereleases are ignored. The repository must remain public for anonymous update checks.

GitHub Actions runs unit tests and release lint on pushes and pull requests. To publish:

1. Increase both `versionCode` and `versionName` in `app/build.gradle` (for example, `5` and `1.4.0-tv`).
2. Commit and push to `main`.
3. Tag that commit `v1.4.0` and push the tag.

The workflow validates the version tag, builds a signed universal APK, uploads `velagram-tv-universal.apk` and `SHA256SUMS`, then publishes the release. The updater requires GitHub's `sha256:` asset digest and that exact APK filename. Wait until the workflow publishes the complete release before expecting update prompts.

Repository secret `ANDROID_KEYSTORE_BASE64` must contain the base64-encoded **existing** `config/velagram-tv.jks`; changing this key breaks upgrades. Back up the key securely. CI verification uses a separate disposable key. Optional secret `ANDROID_SERVICE_CONFIG` can hold the contents of `config/local.properties`; without it, builds use empty service defaults and users can enter supported credentials in Settings. Credentials compiled into a public APK can be extracted, so use appropriately restricted credentials. Signing files and service configuration stay out of Git.

The initial 1.3.0 installation must be sideloaded to gain the updater; older installed versions cannot discover it themselves.
