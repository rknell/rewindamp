#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
repo=rknell/rewindamp
[[ "$(git rev-parse --show-toplevel)" == "$PWD" ]] || { echo 'Use a standalone Rewindamp checkout.' >&2; exit 1; }
[[ "$(git branch --show-current)" == main ]] || { echo 'Release from main.' >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo 'Commit and review source changes first.' >&2; exit 1; }
[[ "$(gh repo view --json nameWithOwner --jq .nameWithOwner)" == "$repo" ]] || { echo 'Wrong release repository.' >&2; exit 1; }
version=$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)
code=$(sed -n 's/.*versionCode \([0-9]*\).*/\1/p' app/build.gradle)
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$code" =~ ^[0-9]+$ ]] || { echo 'Invalid app version.' >&2; exit 1; }
tag="v$version"
notes="docs/releases/$version.md"
[[ -f "$notes" ]] || { echo "Missing $notes" >&2; exit 1; }
git fetch origin main --tags
git merge-base --is-ancestor origin/main HEAD || { echo 'Integrate remote main before releasing.' >&2; exit 1; }
if git rev-parse --verify "refs/tags/$tag" >/dev/null 2>&1; then echo "$tag already exists; inspect its release before retrying." >&2; exit 1; fi
latest=$(gh release view --repo "$repo" --json tagName --jq .tagName)
python3 - "$version" "$latest" <<'PYVERSION'
import sys
current=tuple(map(int,sys.argv[1].split('.')))
previous=tuple(map(int,sys.argv[2].removeprefix('v').split('.')))
assert current>previous, 'versionName must exceed the last published release'
PYVERSION
previous_code=$(git show "$latest:app/build.gradle" | sed -n 's/.*versionCode \([0-9]*\).*/\1/p')
(( code > previous_code )) || { echo 'versionCode must exceed the last published release.' >&2; exit 1; }
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -x "$sdk/build-tools/35.0.0/apksigner" ]] || { echo 'Set ANDROID_HOME to an SDK with build-tools 35.0.0.' >&2; exit 1; }
./gradlew assembleDebug lintDebug
apk=app/build/outputs/apk/debug/app-debug.apk
signer=$("$sdk/build-tools/35.0.0/apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[[ "$signer" == "$(cat release-signing-cert.sha256)" ]] || { echo 'Signing certificate mismatch; do not publish this APK.' >&2; exit 1; }
badging=$("$sdk/build-tools/35.0.0/aapt" dump badging "$apk")
[[ "$badging" == *"package: name='dev.rewindamp' versionCode='$code' versionName='$version'"* ]] || { echo 'APK identity/version mismatch.' >&2; exit 1; }
release_dir=$(mktemp -d)
trap 'rm -rf "$release_dir"' EXIT
asset="Rewindamp-$version.apk"
cp "$apk" "$release_dir/$asset"
(cd "$release_dir" && sha256sum "$asset" > SHA256SUMS)
git tag -a "$tag" -F "$notes"
git push --atomic origin HEAD:main "refs/tags/$tag"
gh release create "$tag" "$release_dir/$asset" "$release_dir/SHA256SUMS" --repo "$repo" --verify-tag --draft --title "Rewindamp $version" --notes-file "$notes"
gh release view "$tag" --repo "$repo" --json assets > "$release_dir/assets.json"
python3 - "$release_dir" "$asset" <<'PY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1]); names={sys.argv[2], 'SHA256SUMS'}
assets=json.loads((root/'assets.json').read_text())['assets']
assert {a['name'] for a in assets} == names, 'Release asset names differ'
for a in assets:
    assert a['state']=='uploaded' and a['size']==(root/a['name']).stat().st_size, 'Release upload incomplete'
PY
gh release edit "$tag" --repo "$repo" --draft=false --latest
gh release view "$tag" --repo "$repo" --json url --jq .url
