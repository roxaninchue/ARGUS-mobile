import csv


def _num(v):
    try:
        return float(v)
    except ValueError:
        return None


def summarize(path):
    with open(path, newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        return {}
    out = {}
    for col in rows[0].keys():
        vals = [r[col].strip() for r in rows if r[col] is not None and r[col].strip() != ""]
        nums = [_num(v) for v in vals]
        if nums and all(n is not None for n in nums):
            out[col] = round(sum(nums) / len(nums), 2)
    return out
