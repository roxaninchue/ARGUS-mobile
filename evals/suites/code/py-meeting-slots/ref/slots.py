import re

_HM = re.compile(r"^([01]\d|2[0-3]):([0-5]\d)$")


def _m(t):
    m = _HM.match(t) if isinstance(t, str) else None
    if not m:
        raise ValueError(f"bad time {t!r}")
    return int(m.group(1)) * 60 + int(m.group(2))


def _hm(x):
    return f"{x // 60:02d}:{x % 60:02d}"


def free_slots(busy, day_start, day_end, minutes):
    start, end = _m(day_start), _m(day_end)
    if start >= end or minutes <= 0:
        raise ValueError("bad day or duration")
    ranges = sorted((_m(a), _m(b)) for cal in busy for a, b in cal)
    out, cur = [], start
    for a, b in ranges:
        if a >= b:
            raise ValueError("busy range must end after it starts")
        stop = min(a, end)
        if stop - cur >= minutes:
            out.append((_hm(cur), _hm(stop)))
        cur = max(cur, b)
    if end - cur >= minutes:
        out.append((_hm(cur), _hm(end)))
    return out
