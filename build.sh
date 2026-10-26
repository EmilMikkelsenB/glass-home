#!/usr/bin/env bash
# Build glass-home.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: ./build.sh [--install <adb serial>]
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$(ls -d /c/Program\ Files/Microsoft/jdk-17* | head -1)}"
SDK="${GLASS_SDK:-$USERPROFILE/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
ART="artwork/out"
PATH="$(cygpath -u "$JAVA_HOME")/bin:$PATH"
if ! command -v adb >/dev/null; then   # winget's platform-tools aren't on Git Bash's PATH
    PT="$(ls -d "$(cygpath -u "${LOCALAPPDATA:-}")"/Microsoft/WinGet/Packages/Google.PlatformTools*/platform-tools 2>/dev/null | head -1)"
    [ -n "$PT" ] && PATH="$PT:$PATH"
fi
# minutes since 1970: always increases, so any newer build (local or CI) installs over an older one
VERSION_CODE="${VERSION_CODE:-$(( $(date +%s) / 60 ))}"
VERSION_NAME="${VERSION_NAME:-dev-$(date +%Y%m%d-%H%M)}"

# artwork from artwork/ (python artwork/make_assets.py)
rm -rf build assets res && mkdir -p build/classes build/dex build/gen assets res/drawable
cp -r res-src/* res/                      # hand-written resources (xml, values); res/ itself is generated
for f in "$ART"/wide/*.png; do cp "$f" "assets/wide_$(basename "$f")"; done
cp "$ART/wallpaper_dark_1920x1080.jpg" assets/wallpaper.jpg   # neutral default; Apple wallpapers are downloaded on the TV
cp "$ART/icons/more.png" res/drawable/icon.png
cp "$ART/banners/more.png" res/drawable/banner.png

"$BT/aapt2.exe" compile --dir res -o build/res.zip
"$BT/aapt2.exe" link -I "$JAR" --manifest AndroidManifest.xml -A assets build/res.zip \
    --java build/gen --min-sdk-version 31 --target-sdk-version 34 -o build/unsigned.apk \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
# LINT=1 (CI): every compiler warning is an error (getOpacity() is a required-but-deprecated Drawable override)
WARN="-nowarn"; [ -n "${LINT:-}" ] && WARN="-Xlint:all,-options,-deprecation -Werror"
javac $WARN -source 11 -target 11 -encoding UTF-8 -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')
"$BT/d8.bat" --min-api 31 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && jar uf ../unsigned.apk classes.dex)
"$BT/zipalign.exe" -f 4 build/unsigned.apk build/aligned.apk

[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias glass -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Glass Home" >/dev/null
"$BT/apksigner.bat" sign --ks debug.keystore --ks-pass pass:android --out glass-home.apk build/aligned.apk
echo "built $(pwd)/glass-home.apk"

if [ "${1:-}" = "--install" ]; then
    adb -s "$2" install -r --no-incremental glass-home.apk
    adb -s "$2" shell cmd package set-home-activity se.glass.home/.MainActivity
    # restart so new code/config loads; a force-stop unbinds the button service, so re-enable it
    adb -s "$2" shell am force-stop se.glass.home
    adb -s "$2" shell settings put secure enabled_accessibility_services se.glass.home/se.glass.home.KeyService
    adb -s "$2" shell input keyevent KEYCODE_HOME
fi
