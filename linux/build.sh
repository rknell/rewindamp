#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

out="$PWD/linux/build"
mkdir -p "$out/input" "$out/classes"
json="$out/input/json-20250517.jar"
checksum=3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796
if [[ ! -f "$json" ]] || ! sha256sum --status -c <<< "$checksum  $json"; then
    curl --fail --silent --show-error --location https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar -o "$json.tmp"
    sha256sum -c <<< "$checksum  $json.tmp"
    mv "$json.tmp" "$json"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
if [[ -d "$out/classes/dev" ]]; then rm -r "$out/classes/dev"; fi
mapfile -d '' sources < <(find shared/src/main/java linux/src/main/java -name '*.java' -print0)
javac --release 17 -encoding UTF-8 -cp "$json" -d "$out/classes" "${sources[@]}"
printf "Main-Class: dev.rewindamp.DesktopApp\nClass-Path: json-20250517.jar\n" > "$out/manifest"
jar --create --file "$out/input/rewindamp.jar" --manifest "$out/manifest" -C "$out/classes" .
cp linux/resources/mpris.py "$out/input/"

case "${1:-}" in
    --run) exec java -cp "$out/input/*" dev.rewindamp.DesktopApp ;;
    --test)
        mkdir -p "$out/tests"
        mapfile -d '' tests < <(find linux/src/test/java -name '*.java' -print0)
        javac --release 17 -encoding UTF-8 -cp "$out/input/*" -d "$out/tests" "${tests[@]}"
        java -ea -cp "$out/tests:$out/input/*" dev.rewindamp.DesktopIntegrationTest "${@:2}"
        ;;
    --package)
        version=$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)
        arch=$(dpkg --print-architecture)
        stage=$(mktemp -d "$out/package.XXXXXXXX")
        trap 'rm -rf "$stage"' EXIT
        jpackage --type app-image --name Rewindamp --app-version "$version" --input "$out/input" --dest "$stage/image" \
            --main-jar rewindamp.jar --main-class dev.rewindamp.DesktopApp --add-modules java.desktop,jdk.httpserver,jdk.crypto.ec
        root="$stage/deb"
        install -d "$root/opt" "$root/usr/bin" "$root/usr/share/applications" "$root/usr/share/doc/rewindamp" "$root/DEBIAN"
        mv "$stage/image/Rewindamp" "$root/opt/rewindamp"
        ln -s /opt/rewindamp/bin/Rewindamp "$root/usr/bin/rewindamp"
        install -m 644 linux/resources/dev.rewindamp.desktop "$root/usr/share/applications/"
        install -Dm644 linux/resources/dev.rewindamp.svg "$root/usr/share/icons/hicolor/scalable/apps/dev.rewindamp.svg"
        install -m644 LICENSE THIRD_PARTY_NOTICES.md "$root/usr/share/doc/rewindamp/"
        cat > "$root/DEBIAN/control" <<CONTROL
Package: rewindamp
Version: $version
Section: sound
Priority: optional
Architecture: $arch
Maintainer: Rewindamp contributors <rknell@users.noreply.github.com>
Homepage: https://github.com/rknell/rewindamp
Depends: ffmpeg, pulseaudio-utils, libsecret-tools, python3-gi, gir1.2-glib-2.0, libx11-6, libxext6, libxi6, libxrender1, libxtst6, libfreetype6, libfontconfig1, fonts-dejavu-core
Description: Rewindamp desktop player for Jellyfin and podcast archives
 Browse music, build queues, and play your own music and podcasts.
CONTROL
        package="Rewindamp-$version-linux-$arch.deb"
        dpkg-deb --root-owner-group --build "$root" "$out/$package"
        (cd "$out" && sha256sum "$package" > "$package.sha256")
        ;;
    "") ;;
    *) printf 'Usage: %s [--run|--test|--package]\n' "$0" >&2; exit 2 ;;
esac
