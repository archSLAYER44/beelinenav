#!/bin/sh
# Builds beelinenav.apk with the Android SDK command-line tools only (no Gradle, no downloads).
# Usage: ./build.sh            build
#        ./build.sh install    build, then install on the phone connected over USB
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/36.1.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
KEYSTORE="$HOME/.android/debug.keystore"

rm -rf build
mkdir -p build/gen build/classes build/dex

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unaligned.apk -I "$PLATFORM" --manifest AndroidManifest.xml -A assets \
    --java build/gen --min-sdk-version 26 --target-sdk-version 34 \
    --version-code 1 --version-name 1.0 build/res.zip

# The map key stays out of git: it is read from ola_key.txt and compiled in. No file = OpenStreetMap.
OLA_KEY=""
if [ -f ola_key.txt ]; then OLA_KEY=$(tr -d '[:space:]' < ola_key.txt); fi
printf 'package dev.guts.beelinenav;\n\nfinal class Secrets {\n    static final String OLA_KEY = "%s";\n}\n' \
    "$OLA_KEY" > build/gen/dev/guts/beelinenav/Secrets.java

javac -encoding UTF-8 --release 11 -Xlint:-options -cp "$PLATFORM" -d build/classes \
    $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" --output build/dex \
    $(find build/classes -name '*.class')

(cd build/dex && "$BT/aapt" add ../unaligned.apk classes.dex >/dev/null)
"$BT/zipalign" -f 4 build/unaligned.apk build/aligned.apk

if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out beelinenav.apk build/aligned.apk
if [ -n "$OLA_KEY" ]; then MAPS="Ola Maps"; else MAPS="OpenStreetMap (no ola_key.txt)"; fi
echo "Built $(pwd)/beelinenav.apk, routing via $MAPS"

if [ "$1" = "install" ]; then
    adb install -r beelinenav.apk
fi
