import math
import re
import sys
from collections import defaultdict

LINE = re.compile(r"^\S+ [A-Z]+ (/[^\s?]*)(?:\?\S*)? \d{3} (\d+)ms$")


def p95(values):
    v = sorted(values)
    return v[max(0, math.ceil(0.95 * len(v)) - 1)]


def slowest(lines, k=3):
    by = defaultdict(list)
    for line in lines:
        m = LINE.match(line.strip())
        if m:
            by[m.group(1)].append(int(m.group(2)))
    ranked = sorted(((p95(v), path) for path, v in by.items()), key=lambda t: (-t[0], t[1]))
    return [(path, ms) for ms, path in ranked[:k]]


def main(argv):
    if len(argv) != 1:
        print("usage: slow.py ACCESS_LOG", file=sys.stderr)
        return 2
    with open(argv[0]) as f:
        for path, ms in slowest(f):
            print(f"{path} {ms}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
