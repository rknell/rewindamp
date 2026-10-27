#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/rewindamp-test.XXXXXXXX")
printf 'Generated test files: %s\n' "$test_dir"
mkdir -p "$test_dir/music/Rewindamp Test/Disposable Signals" "$test_dir/config" "$test_dir/cache"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=440:duration=45' -metadata title='Test Signal One' -metadata artist='Rewindamp Test' -metadata album='Disposable Signals' -c:a flac "$test_dir/music/Rewindamp Test/Disposable Signals/signal-one.flac"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=660:duration=45' -metadata title='Test Signal Two' -metadata artist='Rewindamp Test' -metadata album='Disposable Signals' -c:a libmp3lame "$test_dir/music/Rewindamp Test/Disposable Signals/signal-two.mp3"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'color=c=0x283e57:s=256x256' -frames:v 1 "$test_dir/music/Rewindamp Test/Disposable Signals/folder.jpg"
test_container=$(docker run -d --user "$(id -u):$(id -g)" -p 127.0.0.1:18096:8096 -v "$test_dir/config:/config" -v "$test_dir/cache:/cache" -v "$test_dir/music:/music:ro" jellyfin/jellyfin:10.11)
trap 'docker rm -f "$test_container" >/dev/null' EXIT
python3 scripts/bootstrap-test-server.py
./gradlew connectedDebugAndroidTest "$@"
