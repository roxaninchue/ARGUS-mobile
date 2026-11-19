#!/usr/bin/env python3
"""Hand-picked fixups after tools/rename/rename.py (run from the repo root). Idempotent.

1. Project name: settings.gradle.kts rootProject.name = "Argus" (rename.py keeps exact "Blaze" literals on purpose).
2. Signing prose stays as it is: the release key is still "the private Blaze release key" (signing config untouched).
3. tools/rename/keep-blaze.txt: applicationId and the old-FQCN shim lines no longer apply (owner chose a new
   applicationId com.argus.assistant, so the app installs beside Blaze and no system grant carries over); the permanent
   keeps of this rename are listed with their reasons.
4. Source-scan needles: a test string literal that quotes main code with a lowercase blaze identifier (rename.py keeps
   lowercase inside strings) is rewritten when the old text is no longer in any main source but the renamed text is
   (e.g. ArgusThreadTest "setPerson(blaze())" -> "setPerson(argus())", DuoAll20Test "..., blaze), stream = false").
5. Ratchet the guard baseline (node tools/check-no-blaze.mjs --update-baseline).
"""
import re, subprocess


def sub(path, old, new, count=1):
    s = open(path, encoding='utf8').read()
    if new in s and old not in s:
        return
    if old not in s:
        raise SystemExit(f'{path}: "{old}" not found')
    open(path, 'w', encoding='utf8').write(s.replace(old, new, count))


sub('settings.gradle.kts', 'rootProject.name = "Blaze"', 'rootProject.name = "Argus"')
sub('app/build.gradle.kts', 'private Argus release key', 'private Blaze release key')

KEEP = '''# Permanent allowlist for tools/check-no-blaze.mjs (docs/RENAME-ARGUS-PLAN.md §3).
# One JavaScript regex per line (case-sensitive), then a tab or two spaces and "# reason".
# A match is cut out of the line before the guard counts what is left, so list only what must say blaze forever.
# Temporary leftovers are NOT listed here: tools/rename/baseline.json ratchets them down phase by phase.
# 2026-11-19 package rename (tools/rename/rename.py): the owner chose applicationId com.argus.assistant, so the old
# applicationId line and the old-FQCN shims (accessibility, assistant, tile, widget, launcher alias) no longer apply:
# the new app installs beside Blaze and inherits no system grant. Kept below: what users' data, links and the server use.
const val OLD = "Blaze"|BrandName\\.OLD  # reading text saved before the rename
FOLDER = "Blaze"  # SaveFileAndroid.FOLDER: shared-storage folder name, existing downloads stay findable
blaze://  # deep links and QR codes already in the wild
"blaze_(custom|jobs|routine)"  # notification channel ids keep the user's channel settings
(Music|Movies|Downloads?|Pictures)/Blaze  # old download folders stay scanned
([Hh]ey|OK|Ok|[Ww]ake up) [Bb]laze  # legacy wake phrases keep working
"BLAZE"  # chat JSON role wire value, read forever
blaze\\.db  # database file name kept (plan Phase 5, option A)
blaze-app\\.workers\\.dev  # account-wide workers.dev subdomain, not renamed
X-Blaze-[A-Za-z-]+|x-blaze-[a-z-]+  # gateway wire headers (server names)
\\bblaze_[a-z0-9_]+  # wire fields, tool names, prefs/table/file/channel keys already on phones
blazeinchue|Blazeinchue  # GitLab namespace and Pages host
\\w*blazerouter\\w*  # shared router code (blazerouter/, libblazerouter.so)
'''
open('tools/rename/keep-blaze.txt', 'w', encoding='utf8').write(KEEP)

import glob, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rename as rn
main_text = '\n'.join(open(f, encoding='utf8').read() for f in glob.glob('app/src/main/**/*.kt', recursive=True)
                      + glob.glob('storeapi/src/main/**/*.kt', recursive=True))
for f in glob.glob('app/src/test/**/*.kt', recursive=True) + glob.glob('app/src/androidTest/**/*.kt', recursive=True):
    src = open(f, encoding='utf8').read()
    if 'blaze' not in src:
        continue
    out, changed = [], False
    for kind, t in rn.lex(src):
        if kind == 'string' and 'blaze' in t:
            body = t.strip('"')
            new = rn.edit(body, lower=True)
            if new != body and len(body) >= 6 and body not in main_text and new in main_text:
                t = t.replace(body, new); changed = True
                print(f'  needle {f}: {body[:70]!r}')
        out.append(t)
    if changed:
        open(f, 'w', encoding='utf8').write(''.join(out))

print(subprocess.check_output(['node', 'tools/check-no-blaze.mjs', '--update-baseline'], text=True).strip())
