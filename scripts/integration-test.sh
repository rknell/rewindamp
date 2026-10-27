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
fixture_pid=""
password_pid=""
cleanup() {
    if [[ -n "$fixture_pid" ]]; then kill "$fixture_pid" 2>/dev/null || true; fi
    if [[ -n "$password_pid" ]]; then kill "$password_pid" 2>/dev/null || true; fi
    docker rm -f "$test_container" >/dev/null
}
trap cleanup EXIT
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=440:duration=90' -c:a libmp3lame "$test_dir/signal.mp3"
python3 scripts/podcast-fixture-server.py "$test_dir" > "$test_dir/podcast-server.log" 2>&1 &
fixture_pid=$!
mkdir -p "$test_dir/password-fixture"
cp "$test_dir/signal.mp3" "$test_dir/password-fixture/signal.mp3"
python3 scripts/podcast-fixture-server.py "$test_dir/password-fixture" --port 18099 --password-fixture > "$test_dir/password-server.log" 2>&1 &
password_pid=$!
python3 - <<'WAIT'
import time,urllib.request
for _ in range(50):
    try:
        for port in (18097,18099):urllib.request.urlopen('http://127.0.0.1:'+str(port)+'/health',timeout=1)
        break
    except OSError:time.sleep(.1)
else:raise RuntimeError('Synthetic podcast fixture did not start')
WAIT
python3 scripts/bootstrap-test-server.py
./gradlew connectedDebugAndroidTest "$@"
