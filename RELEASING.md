# Publishing Android updates

Rewindamp updates are delivered through GitHub Releases in `rknell/rewindamp`.
In Obtainium, add `https://github.com/rknell/rewindamp` as a GitHub source.
Each stable release contains one `Rewindamp-<version>.apk` and `SHA256SUMS`.

1. Increase `versionCode` and `versionName` in `app/build.gradle`. Both must advance
   for every published update, including updates previously distributed locally.
2. Add `docs/releases/<version>.md` with the user-visible changes and actual validation.
3. Run the relevant isolated integration checks. Never test against production.
4. Commit the Android source to a clean `main` checkout of `rknell/rewindamp`.
5. With JDK 17, Android SDK 35, authenticated `gh` and Git push access, run:

   ```sh
   ANDROID_HOME=/path/to/android-sdk ./scripts/release.sh
   ```

The script builds and lints, verifies the package, version and signing certificate,
atomically pushes the commit and version tag, then uploads assets to a draft release.
It verifies uploaded asset names and sizes before publishing the release as Latest.
It refuses a dirty checkout, the wrong repository, an existing tag, or a different signer.
If upload fails, retain the draft and recover that same release after inspecting it;
do not issue another version just to retry an upload.

If the working app lives inside the media-server workspace, copy only its source into
a clean checkout of `rknell/rewindamp`, excluding `.git`, `.gradle`, all `build/` folders,
`local.properties`, caches, signing keys, APKs and runtime files. Review the diff before
committing. Never push the containing media-server repository as an Android release.

## Signing continuity

Current personal releases use the build host's existing Android debug key. This preserves
compatibility with Rewindamp APKs already delivered from this host. The public certificate
fingerprint is recorded in `release-signing-cert.sha256`; it is not a private key.
Keep the private key backed up separately. Never commit it or put it in a release.
Do not build published updates on a fresh runner with a newly generated debug key.
Builds remain debug signed; this process does not convert them to store releases.

The old Jellyamp release uses a different application ID. Current `dev.rewindamp` installs
separately and does not migrate Jellyamp data. Updates between current Rewindamp releases
preserve app data when their signer matches.
