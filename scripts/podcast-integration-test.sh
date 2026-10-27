#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
fixture_dir=$(mktemp -d "${TMPDIR:-/tmp}/rewindamp-podcast-test.XXXXXXXX")
printf 'Synthetic podcast test directory: %s\n' "$fixture_dir"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=440:duration=90' -c:a libmp3lame "$fixture_dir/signal.mp3"
python3 scripts/podcast-fixture-server.py "$fixture_dir" > "$fixture_dir/server.log" 2>&1 &
fixture_pid=$!
password_pid=""
queue_pid=""
trap 'kill "$fixture_pid" ${password_pid:+"$password_pid"} ${queue_pid:+"$queue_pid"} 2>/dev/null || true' EXIT
mkdir -p "$fixture_dir/password-fixture"
cp "$fixture_dir/signal.mp3" "$fixture_dir/password-fixture/signal.mp3"
python3 scripts/podcast-fixture-server.py "$fixture_dir/password-fixture" --port 18099 --password-fixture > "$fixture_dir/password-server.log" 2>&1 &
password_pid=$!
mkdir -p "$fixture_dir/queue-fixture"
cp "$fixture_dir/signal.mp3" "$fixture_dir/queue-fixture/signal.mp3"
python3 scripts/podcast-fixture-server.py "$fixture_dir/queue-fixture" --port 18100 --queue-fixture > "$fixture_dir/queue-server.log" 2>&1 &
queue_pid=$!
python3 - <<'PY'
import time,urllib.request
for _ in range(50):
 try:
  for port in (18097,18099,18100):urllib.request.urlopen('http://127.0.0.1:'+str(port)+'/health',timeout=1)
  break
 except OSError:time.sleep(.1)
else:raise RuntimeError('Synthetic podcast server did not start')
PY
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="${PODCAST_TEST_CLASSES:-dev.rewindamp.PodcastIntegrationTest,dev.rewindamp.PodcastQueueIntegrationTest}"

for screen in podcasts player search podcast-discovery; do
    "$ANDROID_HOME/platform-tools/adb" pull "/sdcard/Download/rewindamp-$screen.png" "docs/images/$screen.png"
done
