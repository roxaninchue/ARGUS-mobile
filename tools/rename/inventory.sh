#!/usr/bin/env bash
# Blaze → Argus rename inventory (docs/RENAME-ARGUS-PLAN.md, Phase 0). Run from the repo root:
#   tools/rename/inventory.sh > docs/rename/inventory-$(date +%F).txt
# Each phase commits a fresh inventory so its delta is visible in review.
set -euo pipefail
G=(--exclude-dir=.git --exclude-dir=.gradle --exclude-dir=.kotlin --exclude-dir=build --exclude-dir=node_modules --exclude-dir=jniLibs -I)
echo "# Blaze inventory $(date -u +%FT%TZ) @ $(git rev-parse --short HEAD 2>/dev/null || echo no-git)"
echo "lines: $(grep -rni blaze "${G[@]}" . | wc -l)"
echo "files: $(grep -rli blaze "${G[@]}" . | wc -l)"
echo "paths named *blaze*: $(find . \( -name .git -o -name build -o -name .gradle \) -prune -o -iname '*blaze*' -print | wc -l)"
echo; echo "## files by top-level dir"
grep -rli blaze "${G[@]}" . | cut -d/ -f2 | sort | uniq -c | sort -rn
echo; echo "## by risk class (plan §1)"
c() { printf '%-44s %s\n' "$1" "$(grep -rhoE "$2" "${G[@]}" . | wc -l)"; }
c "D  com.blaze.assistant (package refs)"   'com\.blaze\.assistant'
c "C  Blaze* type names"                    '\bBlaze[A-Z][A-Za-z]*'
c "C  LocalBlaze"                           'LocalBlaze'
c "C  Role/Who .BLAZE enum"                 '\.BLAZE\b'
c "E  blaze.db"                             'blaze\.db'
c "E  getSharedPreferences(\"blaze…\")"     'getSharedPreferences\("blaze[a-z_]*"'
c "E  notification channels blaze_*"        'NotificationChannel\("blaze_[a-z]+"'
c "E  shared folders */Blaze"               '(Music|Movies|Download|Pictures)/Blaze'
c "F  X-Blaze-* headers"                    'X-Blaze-[A-Za-z-]+'
c "F  blaze_* wire fields"                  '\bblaze_(task|avoid|family|models_busy)\b'
c "F  User-Agent Blaze"                     'Blaze(/1|-Android|-Store)'
c "G  *.blaze-app.workers.dev"              '[a-z-]*\.?blaze-app\.workers\.dev'
c "G  blazeinchue.pages.dev"                'blazeinchue\.pages\.dev'
c "G  blaze:// deep links"                  'blaze://'
c "G  libblazerouter / blazerouter"         'blazerouter'
echo; echo "## distinct Blaze* type names"
grep -rhoE '\bBlaze[A-Z][A-Za-z]*' "${G[@]}" --include=*.kt . | sort | uniq -c | sort -rn
echo; echo "## guard (tools/check-no-blaze.mjs)"
node tools/check-no-blaze.mjs || true
