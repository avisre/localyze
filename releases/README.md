# Localyze v1

Installable APK:

- `localyze-v1.apk`
- Version: 1.0.3 / versionCode 4
- SHA-256: `FBD42A45FACC8310CE025532F280049A75B2C0EBD8928E5B3738E27C1B782C3D`

Verification run before packaging:

- `./gradlew.bat testDebugUnitTest`
- `./gradlew.bat lintDebug`
- `./gradlew.bat assembleRelease`
- Android APK Signature Scheme v2 verification passed

Device install smoke test was not run during this packaging pass because no
ADB device or emulator was connected.
