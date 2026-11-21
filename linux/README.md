# Rewindamp for Linux

Rewindamp is a standalone Linux desktop player. It uses the same Jellyfin, Rewindamp Podcasts and Mixarr clients as Android. The desktop package includes a Java 17 runtime and uses FFmpeg for decoding, PulseAudio or PipeWire for output, the desktop keyring for sessions, and MPRIS for media keys and desktop sound controls.

## Install

The Debian package targets amd64 Debian-family desktops, including Ubuntu and Linux Mint. Install a downloaded package with `apt install ./Rewindamp-0.4.18-linux-amd64.deb` (use sudo if your system requires it). Open **Rewindamp** from the application menu, or run `rewindamp`. Try **Demo** to play generated tones with no server credentials.

Connect Jellyfin under **Music** and the separate Rewindamp archive under **Podcasts**. **Search online** opens the Mixarr connection and search. With no active Secret Service keyring, connections work for the current run and require a new sign-in after exit. Use a session with a trusted keyring to keep your sign-ins.

Use **Queue** for either saved queue. Click a track to append it, use **Select multiple** and **Actions** for bulk operations, and drag the right-hand handle to reorder queue entries and playlist tracks. Context menus and Enter work from the keyboard. The letter selector accepts scroll-wheel and arrow input. Click the display clock to switch between elapsed and remaining time. Minimize the window or use **Settings → Close to system tray** to keep listening. Closing the window normally stops playback.

Desktop copies, metadata and queues are private to the Linux user and account under `${XDG_DATA_HOME:-~/.local/share}/rewindamp`. Removing a desktop copy leaves the server file intact. Podcast downloads default to unmetered networks as reported by NetworkManager. If a setup has no metering information, enable **Allow metered downloads** explicitly to download copies.

## Build and test

Run from the checkout root with JDK 17, curl, and the host playback dependencies installed.

```sh
./linux/build.sh --run
./linux/build.sh --package
```

Packaging uses `jpackage` and `dpkg-deb`. The package is written to `linux/build` and uses the Android source version for the common release number. The host's build architecture determines the native launcher architecture.

The integration check uses the same disposable Jellyfin container and Rewindamp podcast fixture as Android, plus generated audio and a disposable PulseAudio or PipeWire sink. It requires Docker, Python 3 and FFmpeg. To run only the self-contained tests without Docker, use `./linux/build.sh --test` with the same sink.

```sh
sink_module=$(pactl load-module module-null-sink sink_name=rewindamp_test)
PULSE_SINK=rewindamp_test ./scripts/integration-test.sh --linux
pactl unload-module "$sink_module"
```

See the [parity checklist](../docs/client-parity.md) before changing server contracts, playback behavior or platform adapters.
