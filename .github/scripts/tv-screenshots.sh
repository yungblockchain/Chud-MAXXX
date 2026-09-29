#!/bin/bash
# Runs inside the Android TV emulator job (.github/workflows/tv-screenshots.yml).
# Installs chud-streams.apk, opens each main screen with remote-control key presses, and saves
# screenshots plus the crash log into the output folder.
#   usage: tv-screenshots.sh <output-dir> <api-level>
#
# Each tab is opened by relaunching the app with the "destination" launch extra
# (MainActivity reads it), so one wrong key press can't derail the rest of the walkthrough.
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
check() {
    if ! alive; then
        echo "::warning::CHUD STREAMS is not running after: $1 (API $API)"
        return 1
    fi
    return 0
}
# Restart the app on one tab: home, library, guide, favorites, markets, games, account, settings.
# The launch animation takes about 3.5 seconds, so the wait includes it.
open_tab() {
    adb shell am start -S -W -n "$PKG/$ACTIVITY" --es destination "$1" >/dev/null
    sleep "${2:-8}"
    hide_keyboard
}

adb install -r chud-streams.apk || { echo "::error::Install failed on API $API"; exit 0; }
adb logcat -c
adb shell am start -n "$PKG/$ACTIVITY"
sleep 1.6; shot 01-launch-logo
sleep 7;   shot 02-first-screen

if check "launch"; then
    # The sign-in form with the keyboard closed and focus on the button.
    hide_keyboard
    for _ in 1 2 3 4 5; do press $DOWN; hide_keyboard; done
    shot 03-sign-in-button

    open_tab markets 12;  shot 04-markets
    press $RIGHT $DOWN $DOWN; sleep 2; shot 05-markets-token
    check "markets"

    open_tab games 8;     shot 06-games
    press $RIGHT;         shot 07-games-focus
    # Snake: open it, start it, let it run for a moment.
    press $OK; sleep 2;   shot 08-snake-ready
    press $OK; sleep 3;   shot 09-snake
    # Back to the menu (focus returns to Snake), then the block game.
    press $BACK; sleep 2
    press $RIGHT $OK; sleep 2; press $OK; sleep 4; shot 10-blocks
    press $BACK; sleep 2
    press $RIGHT $OK; sleep 2; press $OK; sleep 3; shot 11-sky-hop
    press $BACK; sleep 1
    check "games"

    open_tab settings 8;  shot 12-settings
    press $RIGHT; for _ in 1 2 3 4 5 6 7 8; do press $DOWN; done; sleep 1; shot 13-settings-more
    check "settings"

    open_tab guide 8;     shot 14-guide
    open_tab home 8;      shot 15-home
    open_tab account 8;   shot 16-account
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
else
    echo "No crash on API $API."
fi
# GitHub won't take empty files as release assets (an empty crash log means no crash).
find "$OUT" -type f -size 0 -delete
ls -la "$OUT"
