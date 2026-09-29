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
# Type into the focused text field with the on-screen keyboard closed, so the key presses reach
# the field rather than the keyboard.
# A few characters at a time: on Android 11 a long burst loses its tail when the keyboard
# pops back up part-way through.
type_text() {
    local text="$1" i
    hide_keyboard
    for (( i = 0; i < ${#text}; i += 4 )); do
        adb shell input text "${text:i:4}"
        sleep 0.4
        hide_keyboard
    done
    sleep 1
    hide_keyboard
}
# Sign in to the test Xtream server the workflow starts on the runner (the emulator reaches the
# runner at 10.0.2.2). Returns 1 if that server isn't running.
#   usage: sign_in <username> <password> <screenshot-prefix>
sign_in() {
    if ! curl -sf http://127.0.0.1:8080/health >/dev/null; then
        echo "No test Xtream server, so the signed-in screens are skipped."
        return 1
    fi
    open_tab account 8
    type_text "http://10.0.2.2:8080"
    press $DOWN; hide_keyboard
    type_text "$1"
    press $DOWN; hide_keyboard
    type_text "$2"
    shot "$3-sign-in-filled"
    # Past the optional name field to the Sign in button.
    press $DOWN; hide_keyboard
    press $DOWN; hide_keyboard
    press $OK
    sleep 25
    shot "$3-signed-in"
    # The account check and the channel import are separate steps; flag a failed import.
    if adb logcat -d | grep -q "Worker result FAILURE .*SubscriptionWorker"; then
        echo "::error title=Playlist import failed on API $API::Signed in to the test server, but loading its channels failed. See logcat-api$API.txt."
    fi
}

adb install -r chud-streams.apk || { echo "::error::Install failed on API $API"; exit 0; }
adb logcat -c
# Room for a long session's worth of log (the big import is chatty).
adb logcat -G 16M || true
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
    # Nothing has focus after a cold start on this tab: the first key press lands on the top of
    # the menu rail (Home), so walk down to Games and step right into the first game.
    press $RIGHT $DOWN $DOWN $DOWN $DOWN $DOWN $RIGHT; shot 07-games-focus
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
    check "settings and guide"

    # Signed in to the test server: home, library, the timeline guide and the account list.
    if sign_in m3u m3u 17 && check "sign-in"; then
        open_tab home 10;     shot 19-home
        open_tab library 10;  shot 20-library
        open_tab guide 12;    shot 21-guide
        press $RIGHT; sleep 3; shot 22-guide-focus
        press $DOWN; sleep 3;  shot 23-guide-next
        open_tab account 8;   shot 24-account
    fi
    check "walkthrough"

    # A provider-sized account: 50k channels, 130k films (some malformed, slow to start), 20k
    # series. Start from a clean app, sign in, and wait for the import to finish.
    if curl -sf http://127.0.0.1:8080/health >/dev/null; then
        adb shell pm clear "$PKG" >/dev/null
        count_results() { adb logcat -d | grep -c "Worker result $1 .*SubscriptionWorker"; }
        successes=$(count_results SUCCESS)
        failures=$(count_results FAILURE)
        started=$(date +%s)
        if sign_in big big 30 && check "big sign-in"; then
            result=""
            for _ in $(seq 1 120); do
                if [ "$(count_results SUCCESS)" -gt "$successes" ]; then result=ok; break; fi
                if [ "$(count_results FAILURE)" -gt "$failures" ]; then result=failed; break; fi
                if ! alive; then result=died; break; fi
                sleep 5
            done
            seconds=$(( $(date +%s) - started ))
            shot 31-big-after-import
            case "$result" in
                ok) echo "::notice title=Big account on API $API::50k channels, 130k films and 20k series loaded in ${seconds}s." ;;
                failed) echo "::error title=Big account import failed on API $API::See logcat-api$API.txt (SubscriptionWorker)." ;;
                died) echo "::error title=App died during the big import on API $API::See logcat-api$API.txt." ;;
                *) echo "::error title=Big account import still running on API $API::Not finished after ${seconds}s." ;;
            esac
            if [ "$result" = ok ]; then
                # Library: the film playlist opens on its first category; then search all titles.
                open_tab library 12; shot 32-big-library
                press $RIGHT $RIGHT $OK; sleep 6; shot 33-big-films
                press $DOWN; hide_keyboard
                type_text "Film 12345"
                sleep 4; shot 34-big-search
                open_tab guide 14;   shot 35-big-guide
                open_tab home 10;    shot 36-big-home
            fi
            if adb logcat -d | grep -q "OutOfMemoryError"; then
                echo "::error title=Out of memory on API $API::The big account ran the app out of memory."
            fi
        fi
    fi
fi

adb logcat -d > "$OUT/logcat-api$API.txt"
adb logcat -d -b crash > "$OUT/crash-api$API.txt" 2>/dev/null || true
# Keep the crash itself easy to read in the run summary.
# Only this app's crashes count (the emulator's own TV apps crash now and then).
if grep -q "Process: $PKG, PID" "$OUT/logcat-api$API.txt"; then
    grep -B 1 -A 40 "Process: $PKG, PID" "$OUT/logcat-api$API.txt" | head -60 > "$OUT/crash-summary-api$API.txt"
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
