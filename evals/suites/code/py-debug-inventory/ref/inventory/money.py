from decimal import Decimal, ROUND_HALF_UP


def to_cents(amount):
    return int((Decimal(str(amount)) * 100).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def fmt(cents):
    return f"{cents // 100}.{cents % 100:02d}"
