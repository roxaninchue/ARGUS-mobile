#!/usr/bin/env bash
# The YouTube plug-in (Gradle :youtube, private repo Blaze-mobile checked out into youtube/) is REQUIRED: there is no
# build without it. CI runs this right after the plug-in checkout; run it by hand after cloning the plug-in locally.
#
# Build-time rename (2026-11-19, call made without the owner, see overhaul.md Deviations "YouTube plug-in rewrite"):
# the plug-in still implements com.blaze.assistant.mediaapps.store.* from :storeapi, which this repo renamed to
# com.argus.assistant. Until the private repo is renamed upstream, this rewrites the package inside youtube/ only
# (text: com.blaze.assistant -> com.argus.assistant, com/blaze/assistant -> com/argus/assistant; source dirs moved).
# Idempotent: an already renamed plug-in is left as it is.
set -euo pipefail
cd "$(dirname "$0")/../.."

if [ ! -f youtube/build.gradle.kts ]; then
  echo "::error title=YouTube plug-in missing::youtube/build.gradle.kts not found. The YouTube plug-in is required (no build without it): check out the private plug-in repo into youtube/." >&2
  exit 1
fi

# 1. Move every com/blaze/assistant source dir to com/argus/assistant (merging if the target exists).
find youtube -path youtube/.git -prune -o -type d -path '*/com/blaze/assistant' -print | sort -r | while read -r OLD; do
  NEW="${OLD%/com/blaze/assistant}/com/argus/assistant"
  mkdir -p "$NEW"
  (cd "$OLD" && find . -mindepth 1 -maxdepth 1 -print0) | while IFS= read -r -d '' E; do
    if [ -e "$NEW/$E" ]; then cp -a "$OLD/$E/." "$NEW/$E/" && rm -rf "${OLD:?}/$E"; else mv "$OLD/$E" "$NEW/$E"; fi
  done
  rmdir "$OLD" 2>/dev/null || true
  rmdir "${OLD%/assistant}" 2>/dev/null || true
  echo "moved $OLD -> $NEW"
done

# 2. Rewrite the package in every text file.
N=$({ grep -rlIZ --exclude-dir=.git -e 'com\.blaze\.assistant' -e 'com/blaze/assistant' youtube || true; } | tr -cd '\0' | wc -c)
{ grep -rlIZ --exclude-dir=.git -e 'com\.blaze\.assistant' -e 'com/blaze/assistant' youtube || true; } \
  | xargs -0 -r sed -i -e 's/com\.blaze\.assistant/com.argus.assistant/g' -e 's#com/blaze/assistant#com/argus/assistant#g'
echo "rewrote the package in $N file(s)"

# 3. Nothing of the old package may be left.
LEFT=$(grep -rlI --exclude-dir=.git -e 'com\.blaze\.assistant' -e 'com/blaze/assistant' youtube || true)
if [ -n "$LEFT" ] || find youtube -path youtube/.git -prune -o -type d -path '*/com/blaze/assistant' -print | grep -q .; then
  echo "::error title=YouTube plug-in rewrite incomplete::old package still in: $LEFT" >&2
  exit 1
fi
MSG="YouTube plug-in: INCLUDED ($(git -C youtube rev-parse --short HEAD 2>/dev/null || echo 'local copy'), package rewritten to com.argus.assistant at build time)"
echo "$MSG"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "### $MSG" >> "$GITHUB_STEP_SUMMARY"
exit 0
