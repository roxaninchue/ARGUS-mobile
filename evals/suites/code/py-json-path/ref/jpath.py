import re

_TOKEN = re.compile(r"([A-Za-z_][\w-]*)|\[(-?\d+)\]")
_MISSING = object()


def _tokens(path):
    if path == "":
        return []
    out, pos = [], 0
    while pos < len(path):
        if out and path[pos] == "." and pos + 1 < len(path) and path[pos + 1] != "[":
            pos += 1
        m = _TOKEN.match(path, pos)
        if not m or (m.group(1) and out and path[pos - 1] != "."):
            raise ValueError(f"bad path: {path!r}")
        out.append(m.group(1) if m.group(1) else int(m.group(2)))
        pos = m.end()
    return out


def _step(cur, tok):
    if isinstance(tok, int):
        if isinstance(cur, list) and -len(cur) <= tok < len(cur):
            return cur[tok]
        return _MISSING
    if isinstance(cur, dict) and tok in cur:
        return cur[tok]
    return _MISSING


def get(obj, path, default=None):
    cur = obj
    for tok in _tokens(path):
        cur = _step(cur, tok)
        if cur is _MISSING:
            return default
    return cur
