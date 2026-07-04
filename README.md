# PAKFLIX for Android TV & Fire TV

[![Release](https://img.shields.io/github/v/release/sudoaanish/pakflix?style=for-the-badge&color=007200)](https://github.com/sudoaanish/pakflix/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Fire%20TV-38B000?style=for-the-badge&logo=android)](https://developer.android.com/tv)
[![License](https://img.shields.io/badge/License-GPL--2.0-blue.svg?style=for-the-badge)](LICENSE)

**PAKFLIX** is a Jellyfin Android TV fork customized for Pakflix. It keeps the upstream Android TV base and adds Pakflix branding, Fire TV and Android TV sideload install support, GitHub Releases based in-app updates, and release signing checks.

---

## Key Features

- **Leanback 10-Foot UI**: Native TV navigation optimized for Fire TV Remotes and D-pad controls.
- **Hardware Video Acceleration**: Powered by Google Media3 ExoPlayer for low-latency playback and high frame-rate rendering.
- **Over-The-Air Auto Updates**: Built-in update engine that checks GitHub Releases and updates directly on Fire OS devices.
- **Custom TV Launcher Artwork**: Widescreen 16:9 landscape launcher banners tailored for Fire OS and Android TV home screens.
- **Persistent Session Storage**: Auto-reconnects to configured media servers without requiring repeated credentials entry.
- **Dark Obsidian Aesthetic**: Custom UI palette featuring `#0B0E0C` obsidian background with Pakistan Emerald (`#007200`) accents.

---

## Architecture & Technology Stack

- **Language**: Kotlin 1.9+
- **UI Framework**: Jetpack Leanback & AndroidX Compose UI
- **Media Engine**: Google Media3 ExoPlayer & Session API
- **Dependency Injection**: Koin
- **Async & Reactive Flow**: Kotlin Coroutines & StateFlow
- **CI/CD Pipeline**: GitHub Actions for automated release compilation and APK signing

---

## Installation

### Direct Sideload via ADB

Download the latest release APK from [GitHub Releases](https://github.com/sudoaanish/pakflix/releases). Release APKs use names like `pakflix-v0.2.5-release.apk`.

```bash
# Connect to your Android TV / Fire TV device over Wi-Fi
adb connect <DEVICE_IP_ADDRESS>:5555

# Install PAKFLIX APK
adb install -r pakflix-v0.2.5-release.apk

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
