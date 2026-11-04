#!/bin/bash
# Runs inside the Android emulator job: installs the debug app and takes screenshots with sample data.
set -x
cd "$(dirname "$0")/.."
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=io.github.mcas.schiessbuch
mkdir -p shots
adb install -r "$APK"
# the emulator's own launcher sometimes reports "isn't responding" right after boot; hide such system dialogs
adb shell settings put global hide_error_dialogs 1
sleep 30
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
shot() {
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.MainActivity --ez demo true --es lang "$2" ${3:+--es screen $3}
  sleep 6
  adb exec-out screencap -p > "shots/$1.png"
}
for L in de en; do
  adb shell cmd uimode night no;  shot "main-$L-light" $L
  adb shell cmd uimode night yes; shot "main-$L-dark" $L
done
adb shell cmd uimode night no
for S in editor detail settings champ help; do shot "$S-de" de $S; done
adb logcat -d -s AndroidRuntime:E > shots/crash.log || true
ls -la shots
