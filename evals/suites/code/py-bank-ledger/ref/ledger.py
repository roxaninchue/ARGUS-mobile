from decimal import Decimal, ROUND_HALF_UP


class InsufficientFunds(Exception):
    pass


def _money(x):
    d = Decimal(str(x)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    if d <= 0:
        raise ValueError("amount must be positive")
    return d


class Ledger:
    def __init__(self):
        self._bal, self._hist = {}, {}

    def open(self, name):
        if name in self._bal:
            raise ValueError(f"account {name} exists")
        self._bal[name], self._hist[name] = Decimal("0.00"), []

    def _acct(self, name):
        if name not in self._bal:
            raise KeyError(name)
        return name

    def deposit(self, name, amount):
        a = _money(amount)
        self._bal[self._acct(name)] += a
        self._hist[name].append(("deposit", a))

    def withdraw(self, name, amount):
        a = _money(amount)
        if self._bal[self._acct(name)] < a:
            raise InsufficientFunds(name)
        self._bal[name] -= a
        self._hist[name].append(("withdraw", a))

    def transfer(self, src, dst, amount):
        a = _money(amount)
        self._acct(src), self._acct(dst)
        if self._bal[src] < a:
            raise InsufficientFunds(src)
        self._bal[src] -= a
        self._bal[dst] += a
        self._hist[src].append(("transfer_out", a))
        self._hist[dst].append(("transfer_in", a))

    def balance(self, name):
        return self._bal[self._acct(name)]

    def history(self, name):
        return list(self._hist[self._acct(name)])
