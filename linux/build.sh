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
icon="$out/classes/dev.rewindamp.png"
background=$(sed -n 's/.*name="launcher_background">\(#[0-9A-Fa-f]*\)<.*/\1/p' app/src/main/res/values/launcher_colors.xml)
java -Djava.awt.headless=true linux/resources/LauncherIcon.java \
    app/src/main/res/drawable-nodpi/ic_launcher_foreground.png "$background" "$icon"
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
        [[ "$(uname -m)" == x86_64 ]] || { echo 'Release AppImages target x86_64.' >&2; exit 1; }
        command -v zsyncmake >/dev/null || { echo 'Install zsync to build AppImage update metadata.' >&2; exit 1; }
        stage=$(mktemp -d "$out/package.XXXXXXXX")
        trap 'rm -rf "$stage"' EXIT
        jpackage --type app-image --name Rewindamp --app-version "$version" --input "$out/input" --dest "$stage/image" \
            --icon "$icon" --main-jar rewindamp.jar --main-class dev.rewindamp.DesktopApp --add-modules java.desktop,jdk.httpserver,jdk.crypto.ec
        root="$stage/Rewindamp.AppDir"
        mv "$stage/image/Rewindamp" "$root"
        install -m755 linux/resources/AppRun "$root/"
        install -m644 linux/resources/dev.rewindamp.desktop "$icon" "$root/"
        ln -s dev.rewindamp.png "$root/.DirIcon"
        install -d "$root/usr/share/doc/rewindamp"
        install -m644 LICENSE THIRD_PARTY_NOTICES.md "$root/usr/share/doc/rewindamp/"

        mkdir -p "$out/tools"
        appimagetool="$out/tools/appimagetool-1.9.1"
        runtime="$out/tools/runtime-x86_64-20251108"
        fetch() {
            local url=$1 path=$2 digest=$3
            if [[ ! -f "$path" ]] || ! sha256sum --status -c <<< "$digest  $path"; then
                curl --fail --silent --show-error --location --retry 3 "$url" -o "$path.tmp"
                sha256sum -c <<< "$digest  $path.tmp"
                mv "$path.tmp" "$path"
            fi
        }
        fetch https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage \
            "$appimagetool" ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0
        fetch https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64 \
            "$runtime" 2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d
        chmod +x "$appimagetool"

        package="Rewindamp-$version-linux-x86_64.AppImage"
        update='gh-releases-zsync|rknell|rewindamp|latest|Rewindamp-*-linux-x86_64.AppImage.zsync'
        (
            cd "$stage"
            ARCH=x86_64 VERSION="$version" "$appimagetool" --appimage-extract-and-run --no-appstream \
                --runtime-file "$runtime" --updateinformation "$update" --file-url "$package" \
                "$root" "$package"
            test "$(./"$package" --appimage-updateinformation)" = "$update"
            grep -aFxq "URL: $package" "$package.zsync"
            sha256sum "$package" "$package.zsync" > "$package.sha256"
            mv "$package" "$package.zsync" "$package.sha256" "$out/"
        )
        ;;
    "") ;;
    *) printf 'Usage: %s [--run|--test|--package]\n' "$0" >&2; exit 2 ;;
esac
