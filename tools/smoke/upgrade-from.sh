#!/usr/bin/env bash
# Upgrade test for the Blaze → Argus rename (docs/RENAME-ARGUS-PLAN.md §5). Needs adb and one connected phone/emulator.
#   tools/smoke/upgrade-from.sh OLD.apk NEW.apk [outdir]
# 1. installs OLD.apk, 2. waits while YOU seed state on the phone (chat, routine, download, widget, accessibility on,
# default assistant, sign-in), 3. snapshots, 4. installs NEW.apk over it (adb install -r, same applicationId + key),
# 5. snapshots again and diffs. Anything that disappears in the diff is a rename regression.
set -euo pipefail
OLD=${1:?old apk}; NEW=${2:?new apk}; OUT=${3:-upgrade-$(date +%Y%m%d-%H%M%S)}
PKG=com.blaze.assistant   # applicationId: never changes
mkdir -p "$OUT"
snap() {
  local d="$OUT/$1"; mkdir -p "$d"
  adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode|signatures" > "$d/package.txt" || true
  adb shell settings get secure enabled_accessibility_services > "$d/accessibility.txt"
  adb shell settings get secure voice_interaction_service > "$d/assistant.txt"
  adb shell settings get secure assistant >> "$d/assistant.txt"
  adb shell settings get secure sysui_qs_tiles > "$d/qs_tiles.txt"
  adb shell dumpsys appwidget | grep -A2 "$PKG" > "$d/widgets.txt" || true
  adb shell dumpsys notification | grep -E "NotificationChannel\{.*mId='[^']+'" | grep -oE "mId='[^']+'|mImportance=[0-9]+" | paste - - | sort > "$d/channels.txt" || true
  adb shell cmd jobscheduler get-job-state "$PKG" 0 >/dev/null 2>&1 || true
  adb shell dumpsys jobscheduler | grep -c "$PKG" > "$d/jobs_count.txt" || true
  adb shell content query --uri content://media/external/file --projection relative_path:_display_name \
    --where "relative_path LIKE '%/Blaze/%' OR relative_path LIKE '%/Argus/%'" > "$d/media.txt" 2>/dev/null || true
  # Private storage is readable only on debuggable builds; release APKs skip this part.
  if adb shell run-as "$PKG" true 2>/dev/null; then
    adb shell run-as "$PKG" find files databases shared_prefs -maxdepth 2 -type f > "$d/private_files.txt" || true
    for p in $(adb shell run-as "$PKG" ls shared_prefs 2>/dev/null); do
      adb shell run-as "$PKG" cat "shared_prefs/$p" | grep -oE 'name="[^"]+"' | sort > "$d/prefs_${p%.xml}.keys" || true
    done
  else echo "not debuggable: private files skipped" > "$d/private_files.txt"; fi
}
adb install -r -d "$OLD"
echo "Seed state on the phone now (see plan §5), then press Enter."; read -r _
snap before
adb install -r "$NEW"
adb shell am start -W -n "$PKG/com.blaze.assistant.MainActivity" >/dev/null || adb shell monkey -p "$PKG" 1 >/dev/null
sleep 8
snap after
echo "== diff (before → after); lines starting with < were lost"
diff -r "$OUT/before" "$OUT/after" && echo "No differences." || true
echo "Now check by hand: chats, sign-in, a pre-upgrade routine firing, 'Hey Argus' / 'Hey Blaze', blaze:// and argus:// links."
