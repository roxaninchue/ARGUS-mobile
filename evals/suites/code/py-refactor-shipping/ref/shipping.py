RATES = {
    "local": (20, 35, 60, 3),
    "national": (40, 70, 120, 5),
    "international": (150, 260, 480, 12),
}


def _base(weight_kg, zone):
    if zone not in RATES:
        raise ValueError("unknown zone")
    small, medium, large, per_kg = RATES[zone]
    if weight_kg <= 1:
        return small
    if weight_kg <= 5:
        return medium
    return large + max(0, weight_kg - 20) * per_kg


def _member_discount(cost):
    if cost > 100:
        return cost * 0.9
    return cost - 5 if cost > 50 else cost


def shipping_cost(weight_kg, zone, express, member, fragile):
    cost = _base(weight_kg, zone)
    if express:
        cost *= 2 if zone == "international" else 1.5
    if fragile:
        cost += 25 if weight_kg > 5 else 10
    if member:
        cost = _member_discount(cost)
    return round(cost, 2)
