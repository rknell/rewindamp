#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
fixture_dir=$(mktemp -d "${TMPDIR:-/tmp}/rewindamp-podcast-test.XXXXXXXX")
printf 'Synthetic podcast test directory: %s\n' "$fixture_dir"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=440:duration=90' -c:a libmp3lame "$fixture_dir/signal.mp3"
python3 scripts/podcast-fixture-server.py "$fixture_dir" > "$fixture_dir/server.log" 2>&1 &
fixture_pid=$!
trap 'kill "$fixture_pid" 2>/dev/null || true' EXIT
python3 - <<'PY'
import time,urllib.request
for _ in range(50):
 try:urllib.request.urlopen('http://127.0.0.1:18097/health',timeout=1);break
 except OSError:time.sleep(.1)
else:raise RuntimeError('Synthetic podcast server did not start')
PY
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.rewindamp.PodcastIntegrationTest

for screen in podcasts player search podcast-discovery; do
    "$ANDROID_HOME/platform-tools/adb" pull "/sdcard/Download/rewindamp-$screen.png" "docs/images/$screen.png"
done
