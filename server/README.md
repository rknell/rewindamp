# Rewindamp Server

An independent, self-hosted podcast archive and listening-history service, paired with
Rewindamp for Android. Classic listening on modern devices. No Jellyfin dependency,
external accounts, cloud subscription or third-party Python packages are required.

This first version serves podcasts. Music/video scanning, transcoding, multi-user accounts
and an Android TV client are future work. Jellyfin continues to serve the existing music library.

## Run this repository independently

Set absolute persistent directories outside the source checkout, then start the standalone
Compose project. The same image also integrates with the larger media stack described below.

```sh
export REWINDAMP_CONFIG_DIR=/absolute/private/config/rewindamp
export REWINDAMP_MEDIA_DIR=/absolute/media/podcasts
mkdir -p "$REWINDAMP_CONFIG_DIR" "$REWINDAMP_MEDIA_DIR"
docker compose -f server/compose.yaml up -d --build
```

The default direct endpoint is localhost:13510; use a HTTPS reverse proxy for remote devices.
To retrieve the owner token use `docker compose -f server/compose.yaml exec rewindamp-server
python server.py token`. Run backend tests here with `python3 -m unittest discover -s server/tests -v`
and Android tests from the repository root with `./scripts/podcast-integration-test.sh`.

## Run in the media stack

From `media-server-docker-setup/`:

```sh
mkdir -p "../media-server-configuration/rewindamp-server" "../media/podcasts"
docker compose up -d --build rewindamp-server
docker compose ps rewindamp-server
docker compose logs --tail 30 rewindamp-server
```

Use the actual `CONFIG_DIR`/`MEDIA_DIR` from your `.env` if they differ. The non-root
container runs as UID/GID 1000 by default (build arguments follow PUID/PGID); its two
mounted directories must be writable by that user. Nothing is mounted from Jellyfin,
Sonarr, Radarr, the legacy music collection or the Docker socket.

The management UI is at **https://rewindamp.snappyapps.com.au** in this installation.
The direct port defaults to `127.0.0.1:13510`, for local diagnostics only. SWAG connects to
`rewindamp-server:8080` over the Compose network; its source-controlled configuration is
`deploy/rewindamp.subdomain.conf`. The root Compose file includes the subdomain in SWAG's
certificate request. Configure DNS before recreating SWAG for another hostname.

## Connect the app or browser

Sign in with a memorable owner password on the web and Android. There is one shared owner
account; no username or separate family accounts are required in this version.

On first setup, either use the website's **Set password** form with the existing owner token
once, or set your password directly on the host (input is hidden):

```sh
docker compose exec rewindamp-server python server.py password
```

The command asks you to enter and confirm a password of 10–256 characters. It also works
as local password recovery if you forget it. You do not need to restart the service.
The web setup token can be retrieved before setup with `python server.py token` inside the
container. First-time setup never accepts an unauthenticated visitor as the owner.

Once a password is set, the old owner token stops granting API access. Sign in with your
password on the website and install Android 0.4.2 or later for password sign-in. Existing
subscriptions, listening history and phone storage remain in place. Passwords are salted
and hashed using scrypt; neither plaintext passwords nor raw session tokens are stored in
the server database. Sessions expire after 30 days, and sign-in attempts are rate-limited.

Use **PASSWORD** in the web library to change your password. It requires the current password
and signs out other sessions. **SIGN OUT** revokes that browser session. The local password
command also revokes existing sessions. Use HTTPS outside a trusted test network. Browser
sessions stay in tab memory; Android stores only the session token encrypted by Keystore,
never your password. Authentication is always in headers, never media URLs or APK assets.

In Android, select **Podcasts** in the library menu or connect from the login screen without
a Jellyfin account. Music and podcast queues, credentials, phone downloads and history are
separate. Rewindamp uses the clean application ID `dev.rewindamp`; older Jellyamp installs
remain separate. Phone disconnect stops services and removes its saved connection while
retaining app-private downloads for that server.

## Find and add podcasts in Android

Tap **+ FIND PODCASTS** in the Podcasts deck, enter a title or publisher, then tap **GO**
or the keyboard's Search key. Results come from Apple's public iTunes Search API with
`media=podcast`, `entity=podcast`, `country=AU`, and `limit=10`. No Apple API key or account
is needed. Each result shows the podcast title, publisher and genre. Already subscribed
feeds are labelled and cannot be added again from discovery.

Choose **+ SUBSCRIBE**, select a subscription pass, and confirm. The default is future-only;
back-catalogue acquisition requires choosing a date range or All available. Merely searching
or opening a result does not create a subscription or download episodes. After subscribing,
the app returns to the library with that show selected. Manual RSS entry remains in Settings.

The server sends only the search term and fixed catalogue parameters to Apple. It does not
forward its owner token, local listening history or subscriptions. Results are cached for ten
minutes, with a maximum of 15 uncached requests per minute per server. Subscription badges
are refreshed from local state even for cached results. Catalogue failures can be retried;
RSS subscriptions and playback continue independently. Private-feed URL checks still apply
when adding a search result. Re-adding the exact feed URL preserves its existing pass and
future-only baseline rather than overwriting them.

Apple API reference: https://performance-partners.apple.com/search-api

## Initial subscriptions

Seeded once, without overwriting subsequent pass choices:

| Show | Feed | Pass |
|---|---|---|
| 99% Invisible | https://feeds.simplecast.com/BqbsxVfO | Rolling last two years |
| Swindled | https://audioboom.com/channels/5170514.rss | Rolling last 12 months |
| Smosh Reads Reddit Stories | https://rss.amperwave.net/v2/feed/audacynetwork/9c990b304783c10b9f9e1e16a0ac191d | Future episodes only |
| The Louis Theroux Podcast | https://feeds.megaphone.fm/GLT1948741183 | Future episodes only |

RSS is refreshed every 30 minutes. A single background worker downloads selected enclosures
in batches of ten, newest first, and retries failed downloads with backoff. Files become
playable only after a complete, nonempty download is atomically published. Redirects are
limited, checked at every hop, and pinned to resolved public IPs; loopback, private network,
metadata-service addresses, URL credentials and HTTPS downgrades are blocked.

Available passes are **all available**, **future**, **last N years**, **from a date**, and
**manual**. Android offers one/two-year presets and a January 1 shortcut. Calendar-year
windows roll daily; leap days are handled. Undated/future-dated feed entries do not qualify
for date-based acquisition. "All" means episodes still present in the feed, not a publisher's
entire historical catalogue. The feed may stop exposing older episodes.

A future-only subscription establishes its baseline on the first **successful** feed import.
Existing items are remembered without downloading them. Later items qualify only when newly
seen and published after that baseline. Late backfills of old episodes do not become new.
Changing to future-only later uses the original baseline, not a new subscription date.
A failed initial fetch does not accidentally select a back catalogue.

**Passes select new downloads; they do not delete existing archive files.** Older saved files
and episode history remain available when a rolling window moves on. Manual episode requests
persist across refreshes. There is deliberately no server-side media deletion API in this
first version. Phone-copy removal never touches the archive.

The archive download ceiling defaults to **50 GiB** (`REWINDAMP_MAX_ARCHIVE_BYTES`). Individual
episodes are limited to 1 GiB (`REWINDAMP_MAX_EPISODE_BYTES`, optional container environment).
At least 128 MiB of free space is reserved. Errors remain visible and retry; nothing is
silently removed to make room. Backups and metadata are outside that media download ceiling.

## Listening history and playback

Each episode keeps an independent position and state: **unplayed**, **in progress**, **played**
or **dismissed**. Played/dismissed entries are excluded from the unplayed inbox. They can still
be selected explicitly from a show's complete listing. Completing playback or choosing Mark
played completes an episode; seeking close to the end alone does not mark it played.

RSS GUIDs identify episodes within a feed, with enclosure aliases preserving identity when
a publisher changes a GUID but keeps the audio URL. Stable GUIDs also survive enclosure changes.
There is no fuzzy title matching: if a publisher changes both GUID and enclosure URL, an episode
can appear as new. Feed URL migration is not automatically reconciled in this first version.

Android checkpoints positions approximately every three seconds and on playback changes.
Local state and outbound events commit together in one app-private AtomicFile journal. Failed
sync stays queued and retries while the service runs; a manual sync is also available. Event
IDs are idempotent. Completion stays completed despite stale progress reports. Explicit resets
advance a generation, so delayed offline events from before the reset cannot complete it again.
Positions use event time to reject older observations; normal backward seeking remains possible.
Keep device clocks synchronized for accurate cross-device resume ordering.

The podcast session exposes **15 seconds back / 30 seconds forward**, playback speed,
notification controls and its own trusted Android Auto media browser. It uses spoken-audio
attributes, without shuffle, music crossfading or a music EQ/spectrum path. Phone downloads use
Media3's private, server/owner-scoped cache and can be removed independently of history.

The classic browser management UI supports feed/pass editing, played/dismissed/reset controls
and listening. Its simple player loads the complete episode into browser memory before playback;
use Android for streaming, durable offline sync and long listening sessions. Browser progress
requires connectivity and closing a tab during a network failure can lose its last few seconds.
Browser state is not the durable offline journal used by Android.

## Backup and restore

Runtime state is outside the image:

- `${CONFIG_DIR}/rewindamp-server/library.sqlite3`: subscriptions, episode identities, progress,
  event deduplication and archive file references (SQLite WAL).
- `${CONFIG_DIR}/rewindamp-server/owner-token`: sensitive owner credential.
- `${MEDIA_DIR}/podcasts/`: immutable, downloaded episode files, under feed/episode IDs.
- `${CONFIG_DIR}/rewindamp-server/backups/`: automatic daily **database-only** online snapshots.

Database snapshots alone are **not a complete podcast backup**. Create a full archive containing
a consistent database snapshot, the owner credential and every referenced episode file:

```sh
docker compose exec rewindamp-server python server.py backup --full
```

The command prints the container path of its timestamped `.tar` in `/config/backups/`.
Copy that archive to a separate disk or offsite backup system; keeping it beside the running
database protects against mistakes, not disk failure. Full backups are explicit because
repeated copies of a large audio archive consume substantial space. They are not compressed
because episode audio is already compressed. The server does not remove old backups.

To restore, stop the service, extract a **trusted** full archive into a new empty directory,
then copy its `config/` files and `podcasts/` tree into new empty persistent mount directories.
Preserve directory ownership, configure the mounts, and start the service. Do not extract over
a running database or replace existing media without first preserving the current data.
Database episode paths are relative, so restoring into a different media mount works.
Restoring also restores authentication state. Run the local password command after recovery
to revoke restored sessions, especially if a backup has been exposed.

For an existing external backup system, include both persistent roots and use the SQLite
online snapshots (or stop the service) instead of copying only an actively written SQLite file.
Phone-only pending listening events need to sync before they can appear in a server backup.

## Branding assets

The approved transparent pixel-art logo is `static/assets/rewindamp-logo.png`,
served at `/assets/rewindamp-logo.png`. The PNG favicon and multi-size ICO are
nearest-neighbour exports of that master to retain the pixel-art edges.

## API and verification

A bearer session token is required for library/media API routes; auth status and password login are public. There are no tokens in media URLs,
cross-origin API access or public file listings.

- `GET /health`, `GET /api/auth/status` (public setup status), `GET /api/feeds`.
- `POST /api/auth/login`: password → 30-day session access token.
- `POST /api/auth/password`: authenticated initial setup or password change (`new_password`,
  plus `current_password` after setup); revokes other sessions.
- `POST /api/auth/logout`: revokes the current session.
- `GET /api/podcasts/search?term=…`: authenticated Apple Podcasts discovery (AU, ten results).
- `POST /api/feeds`: `url`, `title`, `mode`, `years`, optional Unix-seconds `since`;
  `only_if_new=true` preserves an existing subscription instead of changing its pass.
- `POST /api/feeds/{id}/refresh`: queue a refresh (JSON body `{}`).
- `GET /api/episodes?feed_id=…&unplayed=true&offset=0&limit=100`
- `POST /api/episodes/{id}/download`: select an episode explicitly (JSON body `{}`).
- `GET` / `HEAD /api/episodes/{id}/audio`: authenticated byte ranges for seeking/downloading.
- `POST /api/progress/{id}`: unique `event_id`, current `epoch`, `position` in seconds,
  `observed` Unix seconds, `action` (`progress`, `played`, `dismissed`, `reset`).

Run offline deterministic backend tests from the stack repository:

```sh
python3 -m unittest discover -s rewindamp-server/tests -v
docker compose config --quiet
```

Build Android with JDK 17 and Android SDK 35, then run the synthetic integration fixture with
an emulator. It uses loopback port 18097, an emulator-only account and generated tones; no
production podcasts, credentials or history are used by tests:

```sh
cd rewindamp-android
./gradlew assembleDebug lintDebug
./scripts/podcast-integration-test.sh
```

The tests cover subscription boundaries, leap dates, future-only baseline, failed fetches,
identity aliases, duplicate/stale events, reset generations, backwards seeks, storage limits,
partial downloads, authentication, media byte ranges, full backup restoration and Android
streaming/seeking/downloads that can be read without an upstream source.
