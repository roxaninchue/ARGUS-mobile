#!/usr/bin/env python3
"""Gate shard picker (Finn, 2026-11-25): the unit suite ran one class after another in one job and hit Gate's
75-minute cap. Gate now runs it as N parallel jobs; this prints the Gradle `--tests` filters for shard K of N.

Every test class under app/src/test (a top-level class in a .kt file that has an @Test) goes to exactly one shard,
round-robin over the sorted class names, so the big agent/ and ui/ packages spread evenly.
Usage: gate-shard.py K N   (K is 1-based); prints one argument per line: --tests, <class>, --tests, <class>, ...
       gate-shard.py --list   prints every class once (for a coverage check)."""
import os, re, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "test", "java")
PKG = re.compile(r"^\s*package\s+([\w.]+)", re.M)
CLS = re.compile(r"^(?:(?:internal|public|open|abstract|private)\s+)*class\s+(\w+)", re.M)

def classes():
    out = set()
    for d, _, files in os.walk(ROOT):
        for f in files:
            if not f.endswith(".kt"): continue
            t = open(os.path.join(d, f), encoding="utf-8", errors="replace").read()
            if "@Test" not in t: continue
            m = PKG.search(t); pkg = m.group(1) + "." if m else ""
            for c in CLS.findall(t): out.add(pkg + c)
    return sorted(out)

if __name__ == "__main__":
    cs = classes()
    if sys.argv[1:] == ["--list"]:
        print("\n".join(cs)); sys.exit(0)
    k, n = int(sys.argv[1]), int(sys.argv[2])
    for i, c in enumerate(cs):
        if i % n == k - 1:
            print("--tests"); print(c)
