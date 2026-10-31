# Rewindamp

**Queue a vibe. A classic Winamp-inspired Jellyfin music player for Android.**

Rewindamp makes it quick to turn your own music collection into the soundtrack for a party,
a road trip, or whatever mood you are putting together. Tap a track to start listening,
then keep tapping to build the queue without replacing the song that is playing. Search,
add a bunch of tracks, and save the whole queue as a Jellyfin playlist for next time.

**This project was vibe coded with AI assistance. Contributions are very welcome.**
Bug fixes, better party and playlist workflows, accessibility improvements, testing on real
phones and Android Auto, and thoughtful UI polish all help. See [Contributing](CONTRIBUTING.md).

- Fast, queue-first browsing: additions keep the music going.
- An easy queue and playlist builder for parties and putting together a quick vibe.
- Track-title and artist search that updates as you type, plus a metal letter thumbwheel.
- A native classic-player skin, real ten-band EQ, and a spectrum that moves with the music.
- Gapless playback, optional crossfade, offline downloads, and Android Auto browsing.
- MIT licensed. Bring your own Jellyfin server and account. Android 8.0 or newer.

<p>
  <img src="docs/images/player.png" width="300" alt="Rewindamp classic player, equalizer and queue builder with fictional demo music" />
  <img src="docs/images/search.png" width="300" alt="Artist search with live results and quick queue additions, using fictional demo music" />
</p>

Independent app; unaffiliated with Winamp or Jellyfin. The interface is drawn natively;
no original Winamp skin images are bundled. Demo mode uses fictional titles.

## Podcasts and Rewindamp Server

Rewindamp now includes a separate **Podcasts** deck using the same classic metal buttons,
green LCD and title rails. Choose Podcasts in the library menu, or connect from the login
screen without a Jellyfin account. The independent Docker server downloads RSS episodes,
archives them and synchronizes per-episode listening history. Server deployment, authentication, subscription passes and backup/restore are documented
separately in `rewindamp-server/README.md` in the media-server workspace.

Use **+ Find shows** to search Apple Podcasts' Australian catalogue by title or publisher.
Choose a show, select a subscription pass, then confirm. New subscriptions default to future
episodes only; already subscribed feeds are labelled. Manual RSS entry is also available in
podcast settings. Connect using your owner password after setting it on the server website.
Android stores an encrypted session token rather than the password. Searches use the Rewindamp server; no Apple account or API key is needed.

Podcast controls rewind 15 seconds or advance 30 seconds; playback speed, resume position,
played/dismissed/reset actions and app-private phone downloads are separate from music.
The clean Android application ID is `dev.rewindamp`. It installs separately from Jellyamp;
old Jellyamp app data is not automatically migrated. Local development builds are **0.4.9**.
Music and podcast decks share a horizontal metal destination wheel for Music / Podcasts /
Queue, including their connection screens. Swipe it left or right, or tap either side. Focused search screens use Back to return to the library. Switching tabs leaves playback alone; starting the other kind of audio
uses Android audio focus to pause the first player.

The podcast header has a Settings cog. The single transport row is Previous episode,
15 seconds back, Play/Pause, 30 seconds forward, Next episode, and speed. Long episode titles
scroll without being reset on every clock tick. Tap the green time display to toggle between
elapsed and remaining time, just like music; the podcast choice is remembered across app
restarts. Unknown durations show `--:--` in remaining mode. A remaining-time label and tappable Up Next
strip show where you are and what follows. The pencil beside a selected show edits its
automatic download rule; it is hidden for All Shows. Empty lists offer **View all podcasts**
to browse older episodes outside the download pass.

Choose **Oldest first** or **Newest first** for each show (and separately for All Shows).
Played and dismissed episodes are hidden and skipped by default; enable **Show played and
dismissed episodes** in Settings to include them. Starting an episode snapshots all playable
server/phone-saved episodes in that view into a queue, across catalogue pages. Next follows
that queue's order and selected show; All Shows interleaves shows by publication date.
Unavailable audio is excluded until downloaded. Browsing a different show does not replace
a playing queue; starting another episode creates a new queue from that view. Automatic
completion marks the finished episode played. Queue, position and speed restore paused
after a service restart. Tap an episode row to play or resume it. In-progress episodes show a small progress line and remaining time; the overflow menu retains played/reset, dismiss and phone-copy actions.

Regular Bluetooth/car next and previous controls seek forward 30 seconds and back 15 seconds
within the current podcast, including single-episode queues. The phone’s Previous/Next episode
buttons and queue selection still change episodes; music transport behavior is unchanged.

The moon in the podcast header opens a sleep timer: 15, 30, 60, 90 or 120 minutes, or pause at the
end of the current episode. It runs in the playback service while the screen is off. A timed
pause clears itself; changing episodes cancels an end-of-episode timer.

Settings → **Offline downloads** shows saved/pending/failed counts, phone storage and free
space. View phone copies, retry failed copies through their episode menu, pause/resume
transfers, download the next three unplayed server-saved episodes in the selected view, or
remove completed phone copies with confirmation. Wi-Fi-only downloads are the default and
can be changed. Optional automatic downloads keep the next 1, 3 or 5 queued episodes on the
phone; this starts off and does not expand the server’s subscription pass. Removing all
completed phone copies also switches automatic downloading off. Server archives and history
are kept; phone files remain private to this app and server.

## Install

Get the APK from [GitHub Releases](https://github.com/rknell/rewindamp/releases/latest),
install it, and sign in to your Jellyfin server. To receive updates through **Obtainium**,
choose **Add app** and paste `https://github.com/rknell/rewindamp`. Published releases include
one APK and its checksum. Use HTTPS outside a trusted home network.
Server base paths are supported. You can explore Demo mode before connecting.

The existing published **0.3.6** release uses the former Jellyamp name and application ID.
Current Rewindamp source uses the clean `dev.rewindamp` identity and installs separately;
old app data is not automatically migrated. Builds remain debug signed. Uninstalling either
app removes its phone-only downloads and saved data; server playlists remain on Jellyfin.
The current Rewindamp APK is published on the same Releases page. See
[RELEASING.md](RELEASING.md) for the signing and publishing process.

To make a quick mix: tap tracks to queue them, open **Queue** to reorder or remove entries,
and use **Save As** to keep the mix as a private Jellyfin playlist. Clearing the queue is an
explicit action in the Queue view, so browsing for the next song does not wipe your mix.

## Build your queue

- **Tap a library track to append it immediately.** The first entry starts playing;
  subsequent taps keep the current song and position. Adding to a paused queue leaves it paused.
- **ADD ALL** sits at the very end of the track list and appends the complete view. The entire library is reachable
  in one load, with no pagination or further fetches while scrolling. Only visible rows are
  drawn, so large libraries stay light. Search results and playlist entries also load in full.
- **Turn the metal letter wheel** on the right to jump immediately to a letter, then scroll
  the list on the left to choose a track. The full-height knurled drum has a fixed pointer, green letter
  window and tactile detents. Swipe up for later letters and down for earlier ones, or tap
  above/below the window for one step. It skips letters absent from the current collection
  and follows the first visible row when you scroll the list. Tracks use title initials;
  Artists and Albums use artist initials. Numbers and symbols appear under `#`. Queue,
  album/playlist track order, search relevance and listening history keep their existing
  order and hide the wheel. Accessibility scroll actions and arrow keys also turn it.
- **Tap the green time display** to switch between elapsed and remaining time. Remaining
  time has a minus sign; the choice is remembered across app restarts. Unknown durations
  display `--:--` in remaining mode until the duration becomes available.
- **Previous/Next** are dimmed and disabled when there is no track in that direction, including
  empty and single-track queues. Shuffle uses its actual play order; Repeat All permits wrapping.
  Previous skips to the previous track when available.
- **Queue is its own destination** on the bottom horizontal wheel, beside Podcasts.
  Switch back to Music to restore the same library, album or search result and exact
  scroll position. The remembered location survives screen recreation while in Queue.
  Tap a queue entry to jump to it; hold its **≡** handle and drag to reorder. Hold a track
  for play-next, play-now, playlist, download, favourite and move/remove actions.
- **CLEAR QUEUE** exists only in Queue. Clear/removals have **UNDO**. Undo reinserts the
  removed entries and retains tracks you have added since the removal.
- The queue, current index, playback position, shuffle/repeat and volume are saved in
  account-scoped private storage. Reopening restores it paused, so audio never starts
  unexpectedly. Position is checkpointed approximately every three seconds and on changes.
- **SAVE AS** saves the queue as a private Jellyfin playlist.

## Library, artwork and playlists

The library dropdown switches between Tracks, Albums, Artists, Playlists, Favourites,
Offline and History. **Tracks** is a flat list sorted by track title. **Artists** shows the
same tracks in a flat list sorted by artist, then track title; tap any track to queue it,
with no artist/album navigation. **Albums** is sorted by album artist, then album name;
open an album to see its tracks in disc/track order. The complete phone collection is grouped
alphabetically by the displayed title or artist, including accented initials. Android Auto
keeps its server browsing order.

**Back remembers your place.** Opening an album, playlist or nested library view saves its
parent list and exact scroll offset. Each Back step returns to that location within the
complete loaded collection. Returning from search also restores the list immediately. Android Back and **‹ BACK** behave the same way. Navigation positions survive
screen recreation; lists reload only when needed, including after playlist edits.
Artwork appears in the list and player and is cached for offline use and media notifications.
Missing covers use a small placeholder.

The library panel has one control row below its header, followed immediately by tracks.
The track count appears in the status bar until another message replaces it. The normal
view has no selection toggle, reload button or per-track plus buttons. Playlist creation,
rename/duplicate and whole-view phone downloads are in the library's **⋮** menu.

Tracks and artist lists share an app-private, server/user-scoped metadata cache. Later
launches and view changes show that saved collection immediately, including saved empty
collections, while refreshing silently in the background. Refresh keeps the visible
track and its pixel offset even if earlier entries have changed. Pull down at the top
of the list for an explicit refresh; saved rows remain usable if the server is offline.
Pulling the letter wheel only turns the wheel.

Create private playlists, rename them, duplicate them, add multiple tracks, drag to reorder,
and remove entries with undo. Hold a track and choose **Select multiple tracks** for multi-selection. Bulk actions
include queueing, adding to a playlist, downloading and removing queue/playlist entries.
Edits sync immediately and use the Jellyfin user's existing permissions. Exact playlist-entry
IDs distinguish entries; Jellyfin versions that deduplicate additions retain that behavior.
Media files are never deleted by playlist actions. Whole playlist deletion remains in Jellyfin.

## Search that stays visible

Tap **Search** in the library toolbar to open a dedicated search screen. Its input stays at the top,
above the keyboard, with results in the scrollable area below. Results update after a short
300 ms pause in typing; no button press is needed. Search includes track titles and artist names,
including contributing artists. Title matches appear first, then tracks by matching artists,
without duplicate tracks. Both streams load completely and are deduplicated. Tap several results to append them without closing the keyboard or interrupting
the current song. **Done** or the keyboard's Search key dismisses the keyboard to expose more results.
**‹ BACK** returns to the previous library/playlist location; the bottom wheel opens Queue
and returns to the same search and scroll position.

## Add music through Mixarr

From music search, choose **Can’t find it? Search online**. Sign in with a separate Mixarr
account (the address defaults to `https://mixarr.snappyapps.com.au`), then use one field for
a song, artist, album, or combination. Mixarr searches MusicBrainz with partial-word and typo
matching. Review the title, artist, album, duration and version, then choose **ADD**.
Searching never queues acquisition; only confirming a selected recording does.

You can also paste an HTTPS YouTube video or playlist link into the same field. Video links
show **PREVIEW YOUTUBE IMPORT**: review and edit the title, artist, album and chapter times,
select tracks, then choose **IMPORT SELECTED AUDIO**. Chapter times are seconds from the start
of the video; use **ADD ANOTHER CHAPTER** to split a continuous album manually. Selected chapters
must stay inside the video and cannot overlap. The preview itself never starts a download.

Playlist links show **DOWNLOAD ENTIRE PLAYLIST**. Tapping this queues every available unique
video immediately, up to Mixarr's 100-track limit. Opening the screen with a playlist link does
not queue it. Use `/playlist?list=...` for a playlist; a video link containing `list=...` still
previews only that video. This imports audio once, without setting up playlist synchronization.

The **Library activity** strip opens a dedicated Activity screen for requests and imports.
Search results stay available after adding a recording; acquisition rows never share the results list.
The music deck also shows a compact scrolling activity strip when Mixarr is connected.
Status refreshes every five seconds while that screen or strip is in the foreground.

Recent YouTube imports show per-track states and refresh every five seconds while pending.
Their status survives closing and reopening this screen; full download progress and failed-import
controls are available in Mixarr Activity. Preview tokens stay in memory and are never saved.
Mixarr's media and YouTube acquisition gates, exclusions, cooldowns and existing-audio protection
continue to apply. A matching Mixarr backend with `/api/v1/youtube-imports` support is required.

Activity shows available, queued, processing, paused, completed or failed requests. Progress
updates every five seconds while Activity or online search is open, including the current stage and stream
percentage, bytes, speed and ETA when supplied. Requests continue on Mixarr after leaving the
screen. The last 30 requests (recordings or YouTube import batches) stay on the phone for status checks when you return.
After a failure, **SEARCH AGAIN TO RETRY** obtains a fresh selection; choose Add again to retry.
Reopening a search after returning checks Jellyfin again for newly available tracks.

Mixarr's acquisition/source settings, exclusions, rejected matches and media correction holds
still apply. It reuses active jobs and available recordings and retains confirmed requests
independently of playlists. Mixarr refreshes Jellyfin after acquisition; indexing can take time.
This adds server library media through Mixarr, separate from **Download** phone copies.

The Mixarr session cookie and CSRF token are encrypted with Android Keystore and scoped to the
signed-in Jellyfin account. Jellyfin credentials are never sent to Mixarr. Passwords and selection
tokens are not saved; **MIXARR CONNECTION → DISCONNECT MIXARR** clears the local session and
attempts server logout. HTTPS is required outside local test hosts.

Deploy the matching Mixarr backend for the requester-only progress endpoint:
`GET /api/v1/track-requests/jobs/{job_id}`. The endpoint returns only fixed stages and validated
numeric progress, without download references or other users' request details.

The self-contained Android checks run against an HTTP fixture inside the emulator:

```sh
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.rewindamp.MixarrIntegrationTest
```

## Gapless playback and optional crossfading

Open **SET → Playback transitions**. The setting and crossfade duration persist on the phone:

- **Gapless — continuous albums** (default): Media3's continuous playlist playback, with no
  deliberate silence or overlap. Encoder delay/padding is trimmed when the file supplies the
  necessary information. Network buffering, silence already encoded in a file, or codec/device
  limitations can still produce a gap; downloaded tracks avoid network stalls.
- **Crossfade — overlap songs**: choose 1–12 seconds (default 5). The next item is preloaded and
  two audio players fade in/out with complementary gains. The session, queue and notification
  switch to the incoming track when the overlap begins. The on-screen volume and saved volume
  retain the user's setting. Crossfade is capped at half the outgoing track's length. Unknown
  durations, repeat-one, unavailable successors or songs that aren't ready in time fall back to
  normal playback. Manual seek/skip, queue edits, pause, focus loss and headphones disconnecting
  cancel the overlap so no old track continues playing behind the controls. Metadata/artwork
  updates retain the fade. The spectrum follows the incoming track during an overlap.
- **Separate tracks — short pause**: inserts a 250 ms pause at automatic track boundaries.

Gapless and crossfade are alternative transition modes: use gapless for live albums and other
continuous recordings, and crossfade for mixes. Crossfade requires decoding/buffering two tracks
briefly, increasing battery and network use while active. Its preloader/timer stops when paused
or when another transition mode is selected. Settings apply to the phone and Android Auto.
Physical device/output testing is recommended, especially Bluetooth and network streaming.

## Equalizer and spectrum

The EQ panel has ten peaking filters at 70, 180, 320, 600, 1K, 3K, 6K, 12K, 14K and 16K Hz,
plus preamp, on/off and Flat/Rock/Bass/Vocal/Electronic presets. Gains range from -12 to +12 dB.
Settings persist. Automatic headroom compensates for boosts, so increasing a band may reduce
other frequencies' volume. Extreme preamp settings can still saturate; samples are bounded.

The spectrum analyses decoded PCM after equalization and animates with peak decay. It reacts
to streamed and downloaded music. Animation runs only while the player is actually playing;
pausing, stopping, buffering, playback suppression or an empty queue clears the bars immediately
and cancels animation frames. Hidden/detached views also stop scheduling frames. It resumes
with playback. It does **not** record
microphone audio and requests no recording permission. EQ/spectrum use a PCM path, which
can consume more battery than hardware-offloaded playback. EQ can be collapsed with **EQ**.

## Offline music

Open an album/playlist and choose **⋮ → Download this view to phone**, hold a track and
choose **Download for offline**, or select several tracks from its long-press menu. Downloads use the original files permitted by your Jellyfin user's download policy.
Two files download concurrently, with progress and failure/retry status in **Offline** and an
Android notification. **SET** pauses/resumes downloads. Hold a failed track to retry it.
Android may ask for notification permission; files stay in private app storage.

Completed downloads are preferred automatically. Queued remote entries are upgraded as their
downloads complete; loss of a network or a streaming failure switches to a completed local copy
when one is available. Cached library/playlist screens remain browsable when requests fail.
**Offline** lists downloaded and pending tracks; only completed files can play without a network.
A previously opened/downloaded playlist can also be opened through the cached Playlists browser.

**Remove download from phone** asks for confirmation and removes only the app's downloaded
copy. Server media stays untouched. Signing out stops playback and downloads and keeps saved
queues/downloads private to that server/account. Android app uninstall or clearing app storage
removes its local music. Server-side playlist edits are not automatically mirrored into an
existing offline download selection: download again to add new entries, and remove unwanted
local copies explicitly.

## Favourites and listening history

Hold a track to open its actions and toggle its Jellyfin favourite. Changes are queued locally when
necessary and retried against Jellyfin. Playback start/progress/stop events are sent from the
service, including when the screen is closed. An account-scoped durable outbox retries network
failures, including offline listening, when connected again. **SET → Sync favourites and play
history** triggers another attempt. Jellyfin decides when a title qualifies as played; the
History browser shows Jellyfin's played tracks, ordered by date played. Offline events sync to
the current Jellyfin session when connectivity returns, so historical display times may reflect
sync time. Pending sync work needs the app/service to run; it is not a separate periodic job.

## Android Auto

The exported MediaLibraryService exposes Tracks, Albums, Artists, Playlists, Favourites and
Downloaded music to Android Auto, along with search, voice selection, artwork and normal
transport controls. Sign in on the phone first. Car selections append to the existing queue
and start the selected entry. Browsing only accepts the app's own UID or trusted system/media
controllers, and externally supplied media IDs are resolved through the signed-in Jellyfin
account rather than accepting arbitrary stream URLs.

For a sideloaded build, Android Auto may hide the app until its **Unknown sources** developer
option is enabled. Open Android Auto settings, tap the version/permission section ten times
to enable developer mode, then use Developer settings in the overflow menu. See the
[official Android Auto testing instructions](https://developer.android.com/training/cars/testing#unknown-sources).
A physical car/head-unit test is still needed; development verification exercises Android's
legacy car media browser and voice transport interface on an emulator.

## Privacy and authentication

The session is AES-GCM encrypted with an Android Keystore key. Passwords are not persisted.
Authentication travels in headers, never in stored stream URLs or image URLs. Android backups
are disabled. Queues, metadata, history outboxes, artwork and downloads live in app-private,
server/user-scoped storage; credentials are not bundled with the APK. Signing out clears the
local session and attempts server revocation; if offline, revoke the device in Jellyfin if needed.
No personal library, server credentials or runtime state belong in source or release outputs.

## Build and tests

Use JDK 17 and an Android SDK containing platform/build tools 35, with `ANDROID_HOME` set or
an untracked `local.properties` defining `sdk.dir`. Gradle 8.14, AGP 8.11.1 and Media3 1.8.0
are pinned. Core library desugaring supports Java APIs on older Android versions.

```sh
./gradlew assembleDebug lintDebug
# app/build/outputs/apk/debug/app-debug.apk
```

With an Android emulator running, Docker, ffmpeg and Python 3 available:

```sh
./scripts/integration-test.sh
```

The script also starts a synthetic podcast fixture on loopback port 18097.
The script creates an isolated Jellyfin 10.11 on loopback port 18096 with two generated tones
and a synthetic album cover, and removes only its own test container on exit. It leaves its
printed temporary test directory for inspection. No production mounts, credentials or media
are used. Tests must never target the live server.

The instrumentation suite covers queue appends through the UI, clear/undo, queue restoration,
FLAC/MP3 and server transcoding, background playback, downloads read with no network source,
artwork, actual EQ frequency response and PCM spectrum data, favourite/history sync, playlist
undo/duplication, encrypted sessions, URL validation and car browsing/voice queue selection.
Additional checks verify search remains visible above a real software keyboard, queue additions
from live search, artist-name matches with different track titles, debounced typing and
deduplicated full title/artist results, crossfade handoff/gains/volume persistence and pausing both players, metadata
updates during a fade, gapless boundaries and the optional separate-track pause. A synthetic
225-track HTTP fixture checks a complete collection request, bounded visible rows, cached
connection retry, ordering without duplicates and restoring a deep scroll position after
search. A continuous letter-wheel gesture jumps straight to S without another network
request; ordinary list scrolling updates the selected letter. Library UX checks cover cached
launches (including empty catalogues), refresh failure and inserted rows, private account
scopes, pull gestures, footer actions, the horizontal destination wheel and restoring exact
search/queue positions through activity recreation. Transport checks
cover queue edges, clearing, shuffle, Repeat All and skipping back from a later track.
A deep album-navigation check verifies exact row/pixel restoration through search and both
Back controls, with saved collections reused instead of fetched again. A rendered-title check
verifies that a long title reaches its final characters and loops while playback updates run.
Acquisition checks verify that search results stay visible after adding music, that the main
status strip refreshes and opens Activity, and that failed requests can be searched again.
Podcast transport checks exercise legacy Bluetooth skip commands and raw next/previous key
events, single-episode queues, seeking near the beginning, and separate phone episode navigation.

This is a debug-signed personal build. Store publishing, casting, selectable skins and a compact
mode are not included. Phone and physical-head-unit testing complement the Android 15 emulator
checks; no claims of Play Store or Android Auto certification are made.


## Contributing

This is an actively evolving, vibe-coded personal project, and contributions are welcome.
Open an issue with a reproducible problem or a feature idea, or send a focused pull request.
[CONTRIBUTING.md](CONTRIBUTING.md) covers local builds, safe test fixtures and useful ways to help.
Do not include your Jellyfin password, tokens, private server address or personal library data
in an issue, screenshot or test fixture.

## License

Original Rewindamp code is available under the [MIT License](LICENSE).
Third-party libraries and the Gradle wrapper retain their own licences; see
[Third-party notices](THIRD_PARTY_NOTICES.md).
