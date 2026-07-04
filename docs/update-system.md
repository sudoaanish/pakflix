# Pakflix Update System

Pakflix checks the GitHub Releases latest endpoint for the repository and compares the latest release tag/version with the installed app version. When an update is available, it downloads the APK release asset, stages it through Android's package installer flow, and lets Android perform the in-place update.

Android will only install an in-place APK update when the package name is the same, the incoming `versionCode` is higher than the installed `versionCode`, and the signing certificate matches the installed app. For Pakflix release builds, the package is `com.pakflix.tv`.

## Pre-v0.2.5 Failure

Before `v0.2.5`, a GitHub release APK was signed with the Android Debug certificate while the TV-installed app was signed with the Pakflix release keystore. Android rejected the update because the signatures did not match.

## Current Release Fix

The CI release workflow now signs release APKs with the Pakflix release keystore secrets and verifies the package name, version code, signer fingerprint, and non-debuggable release status before uploading an APK asset.

Current public release signer SHA-256:

```text
359dde3161d91c47a020d2ed40a2b2a223272ac5cfc23cba8d35636759830c51
```

This fingerprint is public verification data. Signing secrets and keystore material must remain private.
