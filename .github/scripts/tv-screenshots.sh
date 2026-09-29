#!/bin/bash
# Runs inside the Android TV emulator job (.github/workflows/tv-screenshots.yml).
# Installs chud-streams.apk, walks through the main screens with remote-control key presses,
# and saves screenshots plus the crash log into the output folder.
#   usage: tv-screenshots.sh <output-dir> <api-level>
set -u
OUT="$1"
API="$2"
PKG=app.dial.tv
ACTIVITY=com.m3u.tv.MainActivity
mkdir -p "$OUT"

UP=19; DOWN=20; LEFT=21; RIGHT=22; OK=23; BACK=4

shot() {
    adb exec-out screencap -p > "$OUT/api$API-$1.png"
    echo "screenshot: $1"
}
alive() { adb shell pidof "$PKG" >/dev/null 2>&1; }
press() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.7; done; }
# Hide the on-screen keyboard if a text field brought it up (Back only closes the keyboard).
hide_keyboard() {
    if adb shell dumpsys input_method | grep -q "mInputShown=true"; then
        adb shell input keyevent $BACK
        sleep 1
    fi
}
# Rail order: Home, Library, Guide, Favorites, Markets, Games, Account, Settings.
open_tab() {
    press $UP $UP $UP $UP $UP $UP $UP $UP $UP
    for _ in $(seq 1 "$1"); do press $DOWN; done
    press $OK
    sleep "${2:-4}"
}
check() {
    if ! alive; then
        echo "::warning::CHUD STREAMS is not running after: $1 (API $API)"
        return 1
    fi
    return 0
}

adb install -r chud-streams.apk || { echo "::error::Install failed on API $API"; exit 0; }
adb logcat -c
adb shell am start -n "$PKG/$ACTIVITY"
sleep 1.6; shot 01-launch-logo
sleep 7;   shot 02-first-screen

if check "launch"; then
    # Leave the sign-in form: move down past the fields (closing the keyboard), then left to the menu.
    hide_keyboard
    for _ in 1 2 3 4 5; do press $DOWN; hide_keyboard; done
    shot 03-sign-in-button
    press $LEFT
    open_tab 4 10; shot 04-markets
    press $RIGHT $DOWN $DOWN; sleep 2; shot 05-markets-token
    press $LEFT $LEFT $LEFT
    open_tab 5 3;  shot 06-games
    press $RIGHT; shot 07-games-focus
    press $OK; sleep 2; press $OK; sleep 3; shot 08-snake
    press $BACK; sleep 2; press $LEFT $LEFT
    open_tab 7 3;  shot 09-settings
    open_tab 2 3;  shot 10-guide
    check "walkthrough"
fi

adb logcat -d > "$OUT/logcat-api$API.txt"
adb logcat -d -b crash > "$OUT/crash-api$API.txt" 2>/dev/null || true
# Keep the crash itself easy to read in the run summary.
if grep -q "FATAL EXCEPTION" "$OUT/logcat-api$API.txt"; then
    grep -A 40 "FATAL EXCEPTION" "$OUT/logcat-api$API.txt" | head -60 > "$OUT/crash-summary-api$API.txt"
    python3 - "$OUT/crash-summary-api$API.txt" "$API" <<'PY'
import sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
body = "\n".join(line[:300] for line in text.splitlines()[:60])
body = body.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
print(f"::error title=Crash on API {sys.argv[2]}::{body}")
PY
fi
ls -la "$OUT"
