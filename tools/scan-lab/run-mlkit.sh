#!/bin/bash
# Runs inside the emulator job of scan-lab.yml: copies the sample photos to the app, lets it read them
# with ML Kit (MainActivity --ez scanlab true) and fetches the result.
set -x
APK=android-native/app/build/outputs/apk/debug/app-debug.apk
PKG=io.github.mcas.schiessbuch
DIR=/sdcard/Android/data/$PKG/files/scanlab
mkdir -p lab
adb install -r "$APK"
adb shell settings put global hide_error_dialogs 1
sleep 20
adb shell mkdir -p $DIR
for f in tools/scan-samples/*.jpg; do adb push "$f" $DIR/; done
adb shell am start -W -n $PKG/.MainActivity --ez scanlab true
for i in $(seq 1 60); do adb shell ls $DIR/mlkit.json >/dev/null 2>&1 && break; sleep 5; done
sleep 3
adb pull $DIR/mlkit.json lab/mlkit.json
adb logcat -d -s AndroidRuntime:E > lab/crash.log || true
ls -la lab
