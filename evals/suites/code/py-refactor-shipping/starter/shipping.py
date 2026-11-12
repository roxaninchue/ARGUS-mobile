def shipping_cost(weight_kg, zone, express, member, fragile):
    cost = 0
    if zone == "local":
        if weight_kg <= 1:
            cost = 20
        else:
            if weight_kg <= 5:
                cost = 35
            else:
                if weight_kg <= 20:
                    cost = 60
                else:
                    cost = 60 + (weight_kg - 20) * 3
    elif zone == "national":
        if weight_kg <= 1:
            cost = 40
        else:
            if weight_kg <= 5:
                cost = 70
            else:
                if weight_kg <= 20:
                    cost = 120
                else:
                    cost = 120 + (weight_kg - 20) * 5
    elif zone == "international":
        if weight_kg <= 1:
            cost = 150
        else:
            if weight_kg <= 5:
                cost = 260
            else:
                if weight_kg <= 20:
                    cost = 480
                else:
                    cost = 480 + (weight_kg - 20) * 12
    else:
        raise ValueError("unknown zone")
    if express:
        if zone == "international":
            cost = cost * 2
        else:
            cost = cost * 1.5
    if fragile:
        if weight_kg > 5:
            cost = cost + 25
        else:
            cost = cost + 10
    if member:
        if cost > 100:
            cost = cost * 0.9
        else:
            if cost > 50:
                cost = cost - 5
    return round(cost, 2)
