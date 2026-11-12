import re

_RE = re.compile(r"(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?")
_MULT = (86400, 3600, 60, 1)


def parse_duration(s):
    if not isinstance(s, str):
        raise ValueError("duration must be a string")
    m = _RE.fullmatch(s.strip().lower())
    if not m or not any(m.groups()):
        raise ValueError(f"bad duration: {s!r}")
    return sum(int(g) * k for g, k in zip(m.groups(), _MULT) if g)
