from .money import to_cents


class Catalog:
    def __init__(self, items=[]):
        self.items = items

    def add(self, sku, name, price):
        self.items.append({"sku": sku, "name": name, "cents": to_cents(price)})

    def page(self, n, size=2):
        start = n * size
        return self.items[start - size:start + 1]
