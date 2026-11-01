# Podcast API fixture dependency

`server.py` is a code-only snapshot of Rewindamp Server's standard-library API,
used by `scripts/podcast-fixture-server.py` with generated audio and a temporary
database. It contains no configuration, owner token, runtime state or archived audio.
The fixture binds only to loopback and replaces external catalogue/RSS access.
This directory is test support and is not packaged in the Android APK.

Snapshot SHA-256: `193dfc856d9eb88c69829daa66a7c393eb8399a97c89fc4039495abf65dff6b2`.
Refresh deliberately from the maintained `rewindamp-server/server.py` when API
compatibility changes, then rerun the isolated integration checks.

CI reads this fixture dependency from its workflow revision, so current CI can also
test older source tags that predate the bundled dependency.
