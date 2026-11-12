"""Builds evals/suites/code/<id>/{task.json, starter/, hidden/, ref/} and evals/suites/code.jsonl.
Run: python3 evals/suites/src/code.py   (then `node evals/run.mjs --suite code --selfcheck` proves every reference
solution passes its hidden tests and every starter fails them)."""
import json, os, shutil, sys
sys.path.insert(0, os.path.dirname(__file__))
import code_tasks_a  # noqa: E402
import code_tasks_b  # noqa: E402,F401  (registers more tasks into code_tasks_a.T)

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, '..', 'code'))
shutil.rmtree(OUT, ignore_errors=True)
index = []
for t in code_tasks_a.T:
    d = os.path.join(OUT, t['id'])
    for part in ('starter', 'hidden', 'ref'):
        for rel, content in t[part].items():
            p = os.path.join(d, part, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
            open(p, 'w').write(content)
    meta = {k: v for k, v in t.items() if k not in ('starter', 'hidden', 'ref')}
    meta.setdefault('protected', []); meta.setdefault('required', [])
    os.makedirs(d, exist_ok=True)
    json.dump(meta, open(os.path.join(d, 'task.json'), 'w'), indent=1)
    index.append({'id': t['id'], 'level': t['level'], 'tags': t['tags'], 'dir': 'code/' + t['id'], 'stretch': bool(t.get('stretch_cmd'))})
ids = [x['id'] for x in index]; assert len(ids) == len(set(ids))
with open(os.path.join(HERE, '..', 'code.jsonl'), 'w') as f:
    for x in index: f.write(json.dumps(x) + '\n')
print(len(index), 'code tasks ->', OUT)
