# Android and Linux parity

Both clients compile the Java source in `shared/src/main/java/dev/rewindamp`. It owns the server clients and media models, account scoping, Jellyfin playlist and reporting APIs, podcast journal and subscription passes, Mixarr request history and chapter validation, ten-band EQ and spectrum processing, and the retro skin palette and clock segments. The two clients keep their own view, download, secure storage and audio-output adapters. Android retains Media3 and Android Auto. Linux uses a Swing desktop window, FFmpeg, the desktop audio server, Secret Service and MPRIS.

| Behavior | Android | Linux |
| --- | --- | --- |
| Jellyfin music | Menu views, complete track and artist search, artwork, account cache, playlist edits, downloads, favourites and history | Same server calls and scope. Desktop controls support wheel/keyboard jumps, selection and queue and playlist reordering. |
| Music playback | Queue append, undo, persistence, shuffle and repeat, three transition modes and ten-band EQ | Same workflows with the native desktop audio backend. Reopening restores the queue paused. |
| Podcasts | Separate account sign-in, subscriptions, discovery and unsubscribe, account-scoped offline state, show orders and filters, queue snapshots, progress sync, sleep and offline copies | Same server and journal. Linux uses NetworkManager's metering state and exposes the same skip intervals through desktop media controls. |
| Mixarr | Search, confirmed requests, activity and recording and YouTube imports | Same Mixarr contract and chapter validation, with a desktop activity window. |
| System controls | Notifications, lock screen, Bluetooth and Android Auto browsing/voice | MPRIS and desktop media keys, with a tray option. Android Auto browsing and voice remain Android features. |
| Power | Media3 retains background audio. Hidden views stop spectrum calculations. Disabled EQ bypasses sample conversion when the spectrum is hidden. | The decoder and output stop when paused. Spectrum calculation and repainting stop when Rewindamp is in the background. The queue checkpoint writes position separately from the track list. Preloading is limited to the imminent boundary and crossfade is opt-in. |

## Changing behavior

1. Put server-facing contracts and pure rules in `shared` so Android and Linux use them directly. Put device-specific playback and OS integration behind the existing platform adapters.
2. Update both entry points in the same change when it affects a shared workflow. Use the corresponding platform row above to record an intentional difference.
3. Exercise the affected scenario through the isolated Android and Linux fixtures. The Android workflow checks the debug APK and emulator scenarios. The Linux workflow exercises the same disposable Jellyfin and podcast servers, synthetic Mixarr requests, persistence, actual FFmpeg/PulseAudio playback and packaging.
4. For audio or background changes, verify paused, playing, hidden and transition states. Include a physical-device or distribution check when the change depends on system audio, networking, desktop keys or battery behavior.

The Flutter desktop toolchain can build native Linux apps. A move to Flutter would require reimplementing the existing Media3, Android Auto and Keystore integrations and migrating on-device state. The shared Java source keeps the current Android adapters and their tests while both clients use the same protocol and DSP implementation. [Flutter desktop support](https://docs.flutter.dev/platform-integration/desktop)

GitHub verifies both clients on the same checkout for each pull request and release tag. Both packages use the Android source version so a release identifies one set of shared behavior. Verified scope and known differences are recorded in release notes. Both clients use gold title rails, recessed green displays, beveled metal controls, and letter and destination wheels. Each client draws those controls with its own toolkit. Matching operating-system gestures, car interfaces and background services requires platform-specific verification.
