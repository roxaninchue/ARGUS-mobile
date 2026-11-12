_VALS = [(1000, "M"), (900, "CM"), (500, "D"), (400, "CD"), (100, "C"), (90, "XC"),
         (50, "L"), (40, "XL"), (10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I")]


def to_roman(n):
    if not isinstance(n, int) or isinstance(n, bool) or not 1 <= n <= 3999:
        raise ValueError("n must be an int in 1..3999")
    out = []
    for v, sym in _VALS:
        q, n = divmod(n, v)
        out.append(sym * q)
    return "".join(out)


_CANON = {to_roman(i): i for i in range(1, 4000)}


def from_roman(s):
    if not isinstance(s, str) or s not in _CANON:
        raise ValueError(f"not a canonical roman numeral: {s!r}")
    return _CANON[s]
