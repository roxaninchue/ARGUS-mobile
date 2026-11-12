from .money import to_cents


class Catalog:
    def __init__(self, items=None):
        self.items = list(items) if items is not None else []

    def add(self, sku, name, price):
        self.items.append({"sku": sku, "name": name, "cents": to_cents(price)})

    def page(self, n, size=2):
        start = (n - 1) * size
        return self.items[start:start + size]
