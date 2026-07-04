# Pakflix Release Process

Release checklist for Pakflix Android TV APKs.

## Version Bump

1. Update the app version metadata in `app/build.gradle.kts`.
2. Bump `versionCode` above the last published release.
3. Keep the release application ID as `com.pakflix.tv`.
4. Keep debug builds on the separate `com.pakflix.tv.debug` package line.

## Commit Process

1. Review the diff before committing.
2. Keep release version/signing workflow changes separate from unrelated feature work.
3. Do not commit keystores, passwords, base64 keystore values, ADB logs, or local machine paths.

## Tag Process

1. Commit the reviewed release change.
2. Create a tag matching the app version, for example `v0.2.5`.
3. Push the commit and tag only after local review passes.
4. Do not reuse or force-update published release tags unless the release history is being repaired on purpose.

## GitHub Actions Release Process

The release workflow builds `:app:assembleRelease`, verifies the APK, and uploads a release asset only if verification passes.

Required GitHub secrets by name:

```text
PAKFLIX_KEYSTORE_BASE64
PAKFLIX_KEYSTORE_PASSWORD
PAKFLIX_KEY_ALIAS
PAKFLIX_KEY_PASSWORD
```

Secret values must stay in GitHub Actions secrets or another approved secret manager. Do not add them to repository files, issue comments, release notes, or logs.

## APK Verification Checklist

Before testing an updater flow, verify the APK that users will actually download from GitHub Releases:

```text
package = com.pakflix.tv
versionCode increases monotonically
signer SHA-256 = 359dde3161d91c47a020d2ed40a2b2a223272ac5cfc23cba8d35636759830c51
debuggable = false
```

## Local Verification Commands

These PowerShell examples use the standard Android SDK user profile path. Adjust `$buildTools` for the installed version on the machine.

```powershell
$apk = "app\build\outputs\apk\release\pakflix-release.apk"
$buildTools = Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools\36.1.0"
$aapt = Join-Path $buildTools "aapt.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"

& $aapt dump badging $apk
& $apksigner verify --print-certs --verbose $apk
```

Useful fields to confirm from the output:

```text
package: name='com.pakflix.tv'
versionCode='...'
versionName='...'
sdkVersion:'...'
targetSdkVersion:'...'
Signer #1 certificate SHA-256 digest: 359dde3161d91c47a020d2ed40a2b2a223272ac5cfc23cba8d35636759830c51
```

Also confirm that the manifest does not mark the release APK as debuggable.

## Release APK Verification Before TV Testing

After GitHub Actions publishes a release, download the release APK from GitHub Releases and verify that downloaded file. Only test the TV updater after the GitHub-hosted APK passes package, version, signer, and non-debuggable checks.
