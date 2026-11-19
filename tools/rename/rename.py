#!/usr/bin/env python3
"""Blaze -> Argus package rename (com.blaze.assistant -> com.argus.assistant), reproducible.

Run from the repo root on a clean checkout of feat/argus-rename (base 7ca549d):
    python3 tools/rename/rename.py            # git mv source dirs + Blaze* files, then rewrite text
    python3 tools/rename/post.py              # hand-picked fixups (project name, guard allowlist, baseline)
    python3 tools/rename/commit.py            # staged commits + pushes
Re-running on an already renamed tree is a no-op (every rule maps old -> new only).

Kept on purpose (never rewritten): server names (X-Blaze-* headers, blaze_* JSON fields, gateway hosts/URLs,
blaze-auth/blaze-gateway, *.blaze-app.workers.dev, blazeinchue.*), blaze:// deep links, blazerouter/ and
libblazerouter.so, persisted keys and file names (anything snake_case or kebab-case or dotted with lowercase "blaze":
prefs names, blaze.db, blaze_* tables/files/channels, blaze-* skill/asset ids, ".blaze" dirs, Music/Blaze folders),
exact "Blaze"/"blaze" string literals (folder names, persona/prefs ids, BrandName.OLD, Jellyfin client id), legacy wake
phrases, the BLAZE enum/wire constant and BLAZE_* env vars, signing config, User-Agent strings (Blaze/1, Blaze-Android),
upstream repo names (Blaze-agent, Blaze-dekstop, Blaze-mobile), and lowercase "blaze" inside Kotlin/Java string
literals (keys, ids, regexes compared against stored text). Identifiers whose Argus name already exists
(BlazeButton, BlazeColors, BlazeTheme) are kept to avoid clashes with the ARGUS kit (see NOTES.md).
"""
import os, re, subprocess, sys

ROOT = os.getcwd()
SRC_ROOTS = ['app/src/main/java', 'app/src/test/java', 'app/src/androidTest/java', 'storeapi/src/main/java']
# Any other source set that has the package (debug/release/sharedTest...) is picked up too.
for base in ('app/src', 'storeapi/src'):
    if os.path.isdir(base):
        for ss in sorted(os.listdir(base)):
            for lang in ('java', 'kotlin'):
                p = f'{base}/{ss}/{lang}'
                if os.path.isdir(f'{p}/com/blaze/assistant') and p not in SRC_ROOTS:
                    SRC_ROOTS.append(p)

# Identifiers whose Argus twin already exists in the code: renaming would clash.
KEEP_IDS = {'BlazeButton', 'BlazeColors', 'BlazeTheme', 'BlazeSpaceHome'}

# Paths never rewritten (prefix match on repo-relative path).
SKIP_PREFIX = ('.git/', 'gateway/', 'blazerouter/', 'wgproxy/', 'keystore/', '.github/', 'evals/',
               'docs/rename/', 'tools/rename/', 'app/src/main/assets/legal/', 'app/src/main/jniLibs/')
SKIP_FILES = {'CHANGELOG.md', 'docs/RENAME-ARGUS.md', 'docs/RENAME-ARGUS-PLAN.md', 'tools/check-no-blaze.mjs',
              'tools/check-no-blaze.test.mjs', 'THIRD_PARTY_NOTICES.md', 'LICENSE.md'}
BIN = re.compile(r'\.(png|jpe?g|gif|webp|ico|jar|so|apk|aab|keystore|jks|p12|zip|gz|xz|tar|ttf|otf|woff2?|mp3|mp4|ogg|wav|onnx|bin|pdf|db|class)$', re.I)
CODE = re.compile(r'\.(kt|kts|java)$')
PROSE = re.compile(r'\.(md|txt)$')

PROTECT = [
    r'[Xx]-[Bb]laze-[\w-]+', r'Blaze-[A-Za-z]\w*', r'Blaze/\d', r'blaze-[\w.-]*\w', r'[\w.-]*\w-blaze\b[\w.-]*',
    r'\w*_blaze\w*', r'blaze_\w*', r'[Bb]lazeinchue\w*', r'blazecoderhue\w*', r'\w*blazerouter\w*',
    r'blaze://[^\s"\'`)]*', r'\bblaze\.[a-z]\w*', r'\.blaze\b', r'@blaze\b',
    r'(?:Music|Movies|Downloads?|Pictures|Documents)/Blaze\b',
    r'(?:[Hh]ey|HEY|OK|Ok|ok|[Ww]ake up)(?:\s|\\\\s\+?)+[Bb]laze\b',
    r'"Blaze"', r"'Blaze'", r'"blaze"', r'Blaze (?:Desktop|CLI|Android|desktop)', r'CN=Blaze', r'\bBLAZE\w*',
    r'\b\w*BLAZE\w*\b',
]
PROTECT_RE = re.compile('|'.join(f'(?:{p})' for p in PROTECT))
WORD = re.compile(r'\b\w*[Bb]laze\w*\b')


def pkg(s):
    return s.replace('com.blaze.assistant', 'com.argus.assistant').replace('com/blaze/assistant', 'com/argus/assistant')


def words(s, lower):
    def w(m):
        t = m.group(0)
        if t in KEEP_IDS or 'BLAZE' in t:
            return t
        t = t.replace('Blaze', 'Argus')
        if lower:
            t = t.replace('blaze', 'argus')
        return t
    return WORD.sub(w, s)


def edit(s, lower):
    """Rewrite one segment: package first, then Blaze words outside the protected spans."""
    s = pkg(s)
    out, i = [], 0
    for m in PROTECT_RE.finditer(s):
        out.append(words(s[i:m.start()], lower)); out.append(m.group(0)); i = m.end()
    out.append(words(s[i:], lower))
    return ''.join(out)


def lex(src, kotlin=True):
    """Split Kotlin/Java source into (kind, text): code, comment, string. Templates ${..} / $id inside strings are code."""
    segs, i, n = [], 0, len(src)
    buf = []

    def flush(kind='code'):
        if buf:
            segs.append((kind, ''.join(buf))); buf.clear()

    def string(i, raw):
        q = '"""' if raw else '"'
        i += len(q); sb = [q]
        while i < n:
            if src.startswith(q, i):
                if raw:
                    while src.startswith('"', i + 3):  # trailing quotes belong to the content
                        sb.append('"'); i += 1
                segs.append(('string', ''.join(sb) + q)); return i + len(q)
            c = src[i]
            if not raw and c == '\\':
                sb.append(src[i:i + 2]); i += 2; continue
            if kotlin and c == '$' and i + 1 < n and src[i + 1] == '{':
                segs.append(('string', ''.join(sb))); sb = []
                j, depth = i + 2, 1
                segs.append(('code', '${'))
                i = code(j, until_brace=True)
                continue
            if kotlin and c == '$' and i + 1 < n and (src[i + 1].isalpha() or src[i + 1] == '_'):
                segs.append(('string', ''.join(sb))); sb = []
                m = re.match(r'\$[A-Za-z_]\w*', src[i:])
                segs.append(('code', m.group(0))); i += m.end(); continue
            sb.append(c); i += 1
        segs.append(('string', ''.join(sb))); return i

    def code(i, until_brace=False):
        depth = 1
        while i < n:
            if src.startswith('//', i):
                flush(); j = src.find('\n', i); j = n if j < 0 else j
                segs.append(('comment', src[i:j])); i = j; continue
            if src.startswith('/*', i):
                flush(); d, j = 1, i + 2
                while j < n and d:
                    if kotlin and src.startswith('/*', j): d += 1; j += 2
                    elif src.startswith('*/', j): d -= 1; j += 2
                    else: j += 1
                segs.append(('comment', src[i:j])); i = j; continue
            if src.startswith('"""', i):
                flush(); i = string(i, True); continue
            c = src[i]
            if c == '"':
                flush(); i = string(i, False); continue
            if c == "'":
                m = re.match(r"'(\\.[^']*|[^'\\])'", src[i:])
                if m:
                    flush(); segs.append(('string', m.group(0))); i += m.end(); continue
            if until_brace:
                if c == '{': depth += 1
                elif c == '}':
                    depth -= 1
                    if depth == 0:
                        buf.append('}'); flush(); return i + 1
            buf.append(c); i += 1
        flush(); return i

    code(0)
    return segs


def rewrite(path, text):
    if CODE.search(path):
        segs = lex(text, kotlin=not path.endswith('.java'))
        if ''.join(t for _, t in segs) != text:
            raise SystemExit(f'lexer lost text in {path}')
        return ''.join(edit(t, lower=(k != 'string')) for k, t in segs)
    if PROSE.search(path):
        return edit(text, lower=True)
    return edit(text, lower=False)


def git(*a):
    return subprocess.check_output(['git', *a], text=True)


def moves():
    moved = 0
    for r in SRC_ROOTS:
        src, dst = f'{r}/com/blaze/assistant', f'{r}/com/argus/assistant'
        if not os.path.isdir(src):
            continue
        moved += int(subprocess.check_output(f'git ls-files "{src}" | wc -l', shell=True))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.exists(dst):
            raise SystemExit(f'{dst} already exists')
        subprocess.check_call(['git', 'mv', src, dst])
        try:
            os.rmdir(f'{r}/com/blaze')
        except OSError:
            pass
    # Files named Blaze* inside the moved packages and the Gradle helpers: rename with the class.
    renamed = 0
    for f in git('ls-files').split('\n'):
        b = os.path.basename(f)
        if 'Blaze' not in b or f.startswith(SKIP_PREFIX) or not CODE.search(f):
            continue
        stem = b.rsplit('.', 1)[0]
        if stem in KEEP_IDS:
            continue
        nf = os.path.join(os.path.dirname(f), b.replace('Blaze', 'Argus'))
        if os.path.exists(nf):
            print(f'  keep {f}: {nf} exists'); continue
        subprocess.check_call(['git', 'mv', f, nf]); renamed += 1
    return moved, renamed


def main():
    moved, renamed = moves()
    edited = 0
    for f in git('ls-files').split('\n'):
        if not f or f in SKIP_FILES or f.startswith(SKIP_PREFIX) or BIN.search(f) or not os.path.isfile(f):
            continue
        raw = open(f, 'rb').read()
        if b'\0' in raw:
            continue
        try:
            text = raw.decode('utf8')
        except UnicodeDecodeError:
            continue
        new = rewrite(f, text)
        if new != text:
            open(f, 'w', encoding='utf8', newline='').write(new); edited += 1
    print(f'moved {moved} files under com/argus/assistant, renamed {renamed} Blaze* files, edited {edited} files')


if __name__ == '__main__':
    main()
