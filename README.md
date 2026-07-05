# PAKFLIX for Android TV & Fire TV

[![Release](https://img.shields.io/github/v/release/sudoaanish/pakflix?style=for-the-badge&color=007200)](https://github.com/sudoaanish/pakflix/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Fire%20TV-38B000?style=for-the-badge&logo=android)](https://developer.android.com/tv)
[![License](https://img.shields.io/badge/License-GPL--2.0-blue.svg?style=for-the-badge)](LICENSE)

**PAKFLIX** is a Jellyfin Android TV fork customized for Pakflix. It keeps the upstream Android TV base and adds Pakflix branding, Fire TV and Android TV sideload install support, GitHub Releases based in-app updates, and release signing checks.

---

## Key Features

- **Android TV / Fire TV UI**: Leanback-based TV navigation with Compose-backed screens where the upstream app uses them.
- **Media3 Playback**: Jellyfin playback modules on Media3 ExoPlayer, with HLS, okhttp datasource, ffmpeg decoder, and subtitle support.
- **GitHub Releases Updater**: In-app update checks against GitHub Releases, versioned APK asset selection, release notes in the prompt, and APK package/version/signer validation before installer launch.
- **Pakflix Branding**: Pakflix app name, launcher banner, icon assets, package ID, and release/debug package separation.
- **Server Session Persistence**: Stored server/session state for reconnecting without re-entering credentials.
- **TV Home Integration**: Android TV channels, Watch Next integration, and launcher metadata inherited from the Jellyfin Android TV base.
- **Pakflix Theme**: Dark green/obsidian resource palette with Pakflix launcher and card/focus styling.

---

## Architecture & Technology Stack

- **Language**: Kotlin 2.4.0
- **Build**: Android Gradle Plugin 9.2.1, Gradle 9.6.1, JDK 17
- **Android Target**: minSdk 23, targetSdk 36, compileSdk 36
- **UI**: AndroidX Leanback, AppCompat Leanback theme, ViewBinding, and AndroidX Compose
- **Playback**: Jellyfin playback modules with AndroidX Media3 ExoPlayer, Session, HLS, okhttp datasource, ffmpeg decoder, and libass subtitles
- **API Client**: Jellyfin Kotlin SDK
- **Dependency Injection**: Koin
- **Async / State**: Kotlin Coroutines, StateFlow, AndroidX Lifecycle
- **Background Work**: AndroidX WorkManager
- **Images**: Coil 3
- **Serialization**: kotlinx.serialization JSON
- **Logging / Crash Reporting**: Timber, SLF4J Timber bridge, ACRA
- **Release Pipeline**: GitHub Actions release builds with Gradle caching, Pakflix release signing, APK verification, and GitHub Release asset upload

---

## Installation

### Direct Sideload via ADB

Download the latest release APK from [GitHub Releases](https://github.com/sudoaanish/pakflix/releases). Release APKs use names like `pakflix-v0.2.7-release.apk`.

```bash
# Connect to your Android TV / Fire TV device over Wi-Fi
adb connect <DEVICE_IP_ADDRESS>:5555

# Install PAKFLIX APK
adb install -r pakflix-v0.2.7-release.apk

# Launch PAKFLIX
adb shell am start -n com.pakflix.tv/org.jellyfin.androidtv.ui.startup.StartupActivity
```

After the first install, Pakflix can check GitHub Releases from inside the app and offer in-place updates. Release APKs install as `com.pakflix.tv`; debug APKs install separately as `com.pakflix.tv.debug`.

## Release Signing

Release APKs are signed with the Pakflix release certificate. GitHub Actions verifies the package name, version code, signer fingerprint, and non-debuggable release status before uploading release assets.

Current public release signer SHA-256:

```text
359dde3161d91c47a020d2ed40a2b2a223272ac5cfc23cba8d35636759830c51
```

This fingerprint is public verification data, not a secret. Do not publish keystore files, passwords, or base64-encoded signing secrets.

---

## Building from Source

### Prerequisites
- JDK 17
- Android SDK 36 (Android 15 target API)
- Gradle 8.x+

### Build Debug APK
```bash
./gradlew :app:assembleDebug
```

### Build Production Release APK
```bash
./gradlew :app:assembleRelease
```

The compiled APKs will be generated in `app/build/outputs/apk/`.

---

## Author & Maintainer

Maintainer: **Aanish Farrukh** ([@sudoaanish](https://github.com/sudoaanish)).

## License

PAKFLIX is distributed under the terms of the GNU General Public License v2.0, consistent with its upstream Jellyfin Android TV lineage.
