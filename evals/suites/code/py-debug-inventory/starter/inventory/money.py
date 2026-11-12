def to_cents(amount):
    return int(float(amount) * 100)


def fmt(cents):
    return f"{cents // 100}.{cents % 100:02d}"
