#!/bin/sh
# Build XMB Home and install it on the TV as the Home app, next to the launcher you have now.
# Usage: TV_HOST=<tv address> ./install.sh      (ADB debugging must be on; accept the prompt on the TV)
set -e
cd "$(dirname "$0")"
: "${TV_HOST:?Set TV_HOST to the TV address, e.g. TV_HOST=192.168.1.50 ./install.sh}"
DEV="$TV_HOST:5555"
./gradlew -q assembleDebug
adb connect "$DEV"
adb -s "$DEV" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$DEV" shell cmd package set-home-activity com.oso.xmbhome/.MainActivity
adb -s "$DEV" shell input keyevent KEYCODE_HOME
echo "Done. To go back: adb -s $DEV shell cmd package set-home-activity <previous.launcher>/.MainActivity"
