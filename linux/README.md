# Rewindamp for Linux

Rewindamp is a standalone Linux desktop player. It uses the same Jellyfin, Rewindamp Podcasts and Mixarr clients as Android. The desktop package includes a Java 17 runtime and uses FFmpeg for decoding, PulseAudio or PipeWire for output, the desktop keyring for sessions, and MPRIS for media keys and desktop sound controls.

## Install

Download the x86-64 `.AppImage` from [GitHub Releases](https://github.com/rknell/rewindamp/releases/latest).
Open it in **Gear Lever** and choose **Move to App Menu** to integrate it. Gear Lever reads the
update source from the file and checks the latest stable release of `rknell/rewindamp`.
Enable **Check updates on system startup** in Gear Lever’s preferences for login notifications,
then apply available updates in Gear Lever. If you have the earlier Debian edition, close it
before opening the AppImage so the new copy can open your existing library.

You can also mark the AppImage executable and run it directly. On systems without working
FUSE, pass `--appimage-extract-and-run`. The AppImage includes Java. It uses your system’s
`ffmpeg` for decoding and `pacat` for audio output, via PulseAudio or PipeWire. On Ubuntu and
Linux Mint those commands are provided by `ffmpeg` and `pulseaudio-utils`. Install them once
if missing. `secret-tool` enables keyring sign-in storage, and Python with PyGObject enables
MPRIS media keys. Try **Demo** to verify playback with generated audio.

Connect Jellyfin under **Music** and the separate Rewindamp archive under **Podcasts**. **Search online** opens the Mixarr connection and search. With no active Secret Service keyring, connections work for the current run and require a new sign-in after exit. Use a session with a trusted keyring to keep your sign-ins.

Use **Queue** for either saved queue. Click a track to append it, use **Select multiple** and the **⋮** menu for bulk operations, and drag the right-hand handle to reorder queue entries and playlist tracks. Context menus and Enter work from the keyboard. Turn the letter wheel with the mouse wheel, a drag, or the Up and Down keys. Use **EQ** to show or hide the equalizer panel. Click the display clock to switch between elapsed and remaining time. Minimize the window or use **Settings → Close to system tray** to keep listening. Closing the window normally stops playback.

Desktop copies, metadata and queues are private to the Linux user and account under `${XDG_DATA_HOME:-~/.local/share}/rewindamp`. The AppImage uses the same storage and keyring entries as the earlier Debian package. Removing a desktop copy leaves the server file intact. Podcast downloads default to unmetered networks as reported by NetworkManager. If a setup has no metering information, enable **Allow metered downloads** explicitly to download copies.

## Build and test

Run from the checkout root with JDK 17, curl, and the host playback dependencies installed.

```sh
./linux/build.sh --run
./linux/build.sh --package
```

Packaging uses a desktop JDK with `jpackage`, `squashfs-tools` and `zsync`. Run it on an
x86-64 Linux host. The build downloads pinned AppImage tools and writes the AppImage,
update metadata and checksums to `linux/build` using the Android source version.

The integration check uses the same disposable Jellyfin container and Rewindamp podcast fixture as Android, plus generated audio and a disposable PulseAudio or PipeWire sink. It requires Docker, Python 3 and FFmpeg. To run only the self-contained tests without Docker, use `./linux/build.sh --test` with the same sink.

```sh
sink_module=$(pactl load-module module-null-sink sink_name=rewindamp_test)
PULSE_SINK=rewindamp_test ./scripts/integration-test.sh --linux
pactl unload-module "$sink_module"
```

See the [parity checklist](../docs/client-parity.md) before changing server contracts, playback behavior or platform adapters.
