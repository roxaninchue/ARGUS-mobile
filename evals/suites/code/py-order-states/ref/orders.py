class InvalidTransition(Exception):
    pass


_NEXT = {
    "pay": ({"new"}, "paid"),
    "ship": ({"paid"}, "shipped"),
    "deliver": ({"shipped"}, "delivered"),
    "cancel": ({"new", "paid"}, "cancelled"),
}


class Order:
    def __init__(self):
        self.state = "new"
        self.history = ["new"]

    def _go(self, action):
        allowed, target = _NEXT[action]
        if self.state not in allowed:
            raise InvalidTransition(f"cannot {action} when {self.state}")
        self.state = target
        self.history.append(target)

    def pay(self):
        self._go("pay")

    def ship(self):
        self._go("ship")

    def deliver(self):
        self._go("deliver")

    def cancel(self):
        self._go("cancel")
