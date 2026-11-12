def merge(intervals):
    items = sorted([list(iv) for iv in intervals])
    out = []
    for s, e in items:
        if s > e:
            raise ValueError(f"bad interval {[s, e]}")
        if out and s <= out[-1][1]:
            out[-1][1] = max(out[-1][1], e)
        else:
            out.append([s, e])
    return out
