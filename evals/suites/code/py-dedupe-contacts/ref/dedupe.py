import csv
import os
import sys


def dedupe(rows, email_col="email"):
    seen, out = set(), []
    for r in rows:
        key = (r.get(email_col) or "").strip().lower()
        if key and key in seen:
            continue
        if key:
            seen.add(key)
        out.append(r)
    return out


def main(argv):
    if len(argv) != 1:
        print("usage: dedupe.py FILE.csv", file=sys.stderr)
        return 2
    src = argv[0]
    with open(src, newline="") as f:
        reader = csv.DictReader(f)
        fields, rows = reader.fieldnames, list(reader)
    kept = dedupe(rows)
    base, ext = os.path.splitext(src)
    with open(base + ".clean" + (ext or ".csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(kept)
    print(f"removed {len(rows) - len(kept)} duplicates")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
