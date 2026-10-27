# Third-party notices

Rewindamp's original code is MIT licensed. That does not change the licences of its build
tools and dependencies. No original Winamp skin assets or Jellyfin server code are included.

- **Gradle wrapper and generated launch scripts**: Apache License 2.0. Copyright belongs to
  the original authors. The scripts retain their original notices and the wrapper JAR
  contains `META-INF/LICENSE`. A copy is in `LICENSES/Apache-2.0.txt`.
  Upstream: https://github.com/gradle/gradle
- **AndroidX Media3 and AndroidX Core**: Apache License 2.0.
  Upstream: https://github.com/androidx/media and https://github.com/androidx/androidx
- **Android test libraries**: Apache License 2.0; used for instrumentation tests.
  Upstream: https://github.com/android/android-test
- **Core library desugaring**: provided by Android tooling; its upstream distribution retains
  its applicable notices. Upstream: https://github.com/google/desugar_jdk_libs

Dependency versions are declared in `app/build.gradle` and `build.gradle`. Consult upstream
source and published artefacts for their complete notices and transitive dependency licences.
