# Rewindamp Android

Read README.md before changing this app. Build with JDK 17 and Android SDK 35;
run `./gradlew assembleDebug lintDebug` and relevant isolated integration checks.
Never use production services or include credentials, app data or signing keys in Git/APKs.

## Delivery preference

The owner wants completed Android updates published to GitHub Releases at
https://github.com/rknell/rewindamp so Obtainium can install them. Publish a new
version after implementing and validating requested changes; return the release link
instead of a local APK attachment. This is the owner's standing delivery instruction.
Follow RELEASING.md. Increase both versionCode and versionName, include release notes,
and keep the existing app ID and signing certificate for update compatibility.

The app may be edited inside a larger media-server repository. Publish only the Android
source from a clean checkout of rknell/rewindamp; never push the parent repository or
unrelated changes. Exclude build output, local.properties, .gradle, keys and runtime data.
Signing keys remain private on the build host. If the matching key is unavailable,
report the blocker; do not silently switch signatures.
