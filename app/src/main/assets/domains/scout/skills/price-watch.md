---
name: price-watch
description: Watch a product's price across stores and report only when it reaches the user's target, with total landed cost and delivery to their location. Use for "tell me when this gets cheaper", "watch the price of X" or the daily price-drop routine.
tags: [shopping, price, deals, tracking]
version: 1
---

# Price watch

1. Record the product (exact model), stores to watch, the user's target price and currency, and the delivery location.
2. On each check, read the current price, delivery fee, stock status and expected delivery from each store.
3. Compute total landed cost (price plus delivery plus any duties or fees).
4. Keep the lowest price seen in 90 days; treat a "was" price never seen before as suspect.
5. Report only when the total reaches the target or stock is about to run out, with the link and the store's return policy.
6. Stay silent when nothing changed.
7. Buying is an approval card; the shopper worker stops at the payment page.
