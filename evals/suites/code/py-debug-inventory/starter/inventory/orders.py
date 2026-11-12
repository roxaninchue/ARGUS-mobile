from .money import fmt


def order_total(catalog, lines):
    prices = {i["sku"]: i["cents"] for i in catalog.items}
    return fmt(sum(prices[sku] * qty for sku, qty in lines))
