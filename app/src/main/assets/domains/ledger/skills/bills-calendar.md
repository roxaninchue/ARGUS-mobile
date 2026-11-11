---
name: bills-calendar
description: Keep the user's bills calendar - recurring bills with payee, amount, due date and channel, matched against payments seen in bank and mobile-money SMS. Use for "what bills are due", "did I pay ZESCO" or the daily bills routine.
tags: [money, bills, budget, reminders]
version: 1
---

# Bills calendar

1. List the recurring bills the user confirmed: payee, usual amount with currency, due day, and payment channel (bank, mobile money, debit order).
2. Read bank and mobile-money SMS since the last check; match each payment to a bill by payee and amount (within 10 %).
3. For each bill due in the next 3 days that has no matching payment, prepare a reminder: payee, amount, due date, channel.
4. Flag a bill whose amount changed by more than 5 % from its usual value.
5. Show due bills as a short list, soonest first; stay silent when nothing is due.
6. Never pay or schedule a payment: the user pays, or approves a payment card, themselves.
7. Bill details stay in the private money scope.
