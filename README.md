# PAKFLIX for Android TV & Fire TV

[![Release](https://img.shields.io/github/v/release/sudoaanish/pakflix?style=for-the-badge&color=007200)](https://github.com/sudoaanish/pakflix/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Fire%20TV-38B000?style=for-the-badge&logo=android)](https://developer.android.com/tv)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg?style=for-the-badge)](LICENSE)

**PAKFLIX** is a high-performance, native media client designed specifically for Smart TVs, Android TV devices, and Amazon Fire TV. Engineered with modern Android development practices, Kotlin, Jetpack Leanback, and Media3 ExoPlayer, PAKFLIX delivers a fluid 10-foot user experience tailored for remote navigation and high-definition video playback.

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
```bash
# Connect to your Android TV / Fire TV device over Wi-Fi
adb connect <DEVICE_IP_ADDRESS>:5555

# Install PAKFLIX APK
adb install -r PAKFLIX-v0.1.1.apk

# Launch PAKFLIX
adb shell am start -n com.pakflix.tv/org.jellyfin.androidtv.ui.startup.StartupActivity
```

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

Developed and maintained by **Aanish Farrukh** ([@sudoaanish](https://github.com/sudoaanish)).

## License

PAKFLIX is distributed under the terms of the GNU General Public License v3.0.
