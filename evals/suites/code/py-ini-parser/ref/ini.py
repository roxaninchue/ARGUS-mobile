import re

_SECTION = re.compile(r"^\[([^\]]+)\]$")


def parse_ini(text):
    data = {"": {}}
    cur = ""
    for no, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line[0] in ";#":
            continue
        m = _SECTION.match(line)
        if m:
            cur = m.group(1).strip()
            data.setdefault(cur, {})
        elif "=" in line and line.split("=", 1)[0].strip():
            key, val = line.split("=", 1)
            data[cur][key.strip()] = val.strip()
        else:
            raise ValueError(f"line {no}: cannot parse {raw!r}")
    if not data[""]:
        del data[""]
    return data
