# Podcast API fixture dependency

`server.py` is a code-only snapshot of Rewindamp Server's standard-library API,
used by `scripts/podcast-fixture-server.py` with generated audio and a temporary
database. It contains no configuration, owner token, runtime state or archived audio.
The fixture binds only to loopback and replaces external catalogue/RSS access.
This directory is test support and is not packaged in the Android APK.

Snapshot SHA-256: `d9944d0270ea2817f2797fa080bb31870c36570f5f06121f6fe32e8598c95b8b`.
Refresh deliberately from the maintained `rewindamp-server/server.py` when API
compatibility changes, then rerun the isolated integration checks.

CI uses the selected source revision’s fixture whenever it exists and uses the workflow
revision’s fixture for older source tags without a bundled dependency.
