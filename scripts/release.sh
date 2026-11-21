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
git tag -a "$tag" -F "$notes"
git push --atomic origin HEAD:main "refs/tags/$tag"

for attempt in {1..20}; do
    run=$(gh run list --repo "$repo" --workflow release.yml --branch "$tag" --event push --limit 1 --json databaseId --jq '.[0].databaseId')
    if [[ -n "$run" ]]; then
        gh run watch "$run" --repo "$repo" --exit-status
        gh release view "$tag" --repo "$repo" --json url --jq .url
        exit 0
    fi
    sleep 2
done
echo "GitHub has not returned the release workflow yet. Check Actions for $tag before retrying." >&2
exit 1
