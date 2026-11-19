#!/usr/bin/env python3
"""Commit and push the rename in stages on feat/argus-rename (run from the repo root after rename.py + post.py).

  python3 tools/rename/commit.py            # every stage that has changes, pushing after each
  python3 tools/rename/commit.py --dry-run  # show what each stage would take

Stage 1: the git mv's only (index as rename.py left it), so history follows the files.
Stage 2: Kotlin/Java source edits (package, imports, Blaze* identifiers).
Stage 3: config, manifest, ProGuard, resources, assets, CI paths and docs.
Stage 4: the rename tooling, guard allowlist and baseline, NOTES and bookkeeping (CHANGELOG, overhaul.md).
Never pushes anywhere but origin feat/argus-rename, always with -o ci.skip; titles end with [skip ci].
"""
import subprocess, sys

BRANCH = 'feat/argus-rename'
DRY = '--dry-run' in sys.argv
STAGE4 = ('tools/rename/', 'CHANGELOG.md', 'overhaul.md')


def git(*a, check=True):
    r = subprocess.run(['git', *a], text=True, capture_output=True)
    if check and r.returncode:
        raise SystemExit(f'git {" ".join(a)} failed:\n{r.stdout}{r.stderr}')
    return r.stdout


def staged():
    return [l for l in git('diff', '--cached', '--name-only').split('\n') if l]


def changed():
    out = git('status', '--porcelain', '--untracked-files=all')
    return [l[3:].split(' -> ')[-1] for l in out.split('\n') if l]


def commit(title, body=''):
    if not staged():
        print(f'skip (nothing staged): {title}'); return
    print(f'{"would commit" if DRY else "commit"} {len(staged())} files: {title}')
    if DRY:
        git('reset', '-q'); return
    git('commit', '-q', '-m', title + ' [skip ci]', *(['-m', body] if body else []))
    push()


def push():
    git('pull', '--rebase', '--autostash', '-q', 'origin', BRANCH)
    git('push', '-q', '-o', 'ci.skip', 'origin', f'HEAD:{BRANCH}')
    print('  pushed', git('rev-parse', '--short', 'HEAD').strip())


if git('rev-parse', '--abbrev-ref', 'HEAD').strip() != BRANCH:
    raise SystemExit(f'not on {BRANCH}')

# Stage 1: only what is already in the index (pure renames from rename.py).
idx = git('diff', '--cached', '--name-status', '-M100%')
if idx.strip():
    commit('Rename 1/4: move com/blaze/assistant to com/argus/assistant and Blaze*.kt to Argus*.kt (git mv only)',
           'Pure moves so git log --follow keeps history; the package/import edits are the next commit.')

files = changed()
code = [f for f in files if f.endswith(('.kt', '.java')) and f.startswith(('app/src/', 'storeapi/src/'))]
if code:
    git('add', '--', *code)
    commit('Rename 2/4: package com.argus.assistant, imports and Blaze* identifiers in Kotlin sources',
           'Scripted by tools/rename/rename.py. Kept: wire names, persisted keys, blaze:// links, BLAZE wire constant, '
           'BlazeButton/BlazeColors/BlazeTheme (Argus twins exist).')

files = changed()
rest = [f for f in files if not f.startswith(STAGE4)]
if rest:
    git('add', '-A', '--', *rest)
    commit('Rename 3/4: namespace + applicationId com.argus.assistant, project Argus, manifest, ProGuard, assets, CI paths, docs')

files = changed()
if files:
    git('add', '-A', '--', *files)
    commit('Rename 4/4: rename scripts and notes in tools/rename, guard allowlist + baseline, CHANGELOG, overhaul Deviations')
