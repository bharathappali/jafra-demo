# Line price cache

Smart Wallet carts miss the line-price cache, so each line walks the full promotion book. Warmup traffic mostly repeats a small set of keys and stays near 0.65 cores. During the 7.2 rollout the same request rate costs about 1.3 cores. Allocation barely changes, because the extra time is the walk, not extra garbage.

## Cause

`LinePriceKey` is documented as everything that influences the promotional unit price, and it stores the shopper's coupon list. `CouponCodes.normalize` trims, uppercases, and drops blanks and duplicates. It does not sort. `PriceQuoteService` loads that key with `getIfPresent` and, on a miss, calls `PromotionEngine.applyPromotions`, which evaluates every promotion.

Smart Wallet sends 3–6 codes drawn from the first 14 published codes, in shuffled "most recently used" order. A quarter of those carts also lowercase the first code. Normalization removes the case difference and leaves the order in the key. Ordered draws of 3–6 codes from 14 are about 2.4 million distinct lists. Those lists are different keys even when they select the same promotions, because eligibility only cares which coupon ids are present.

At 120 requests per second almost every wallet cart is a key the cache has never seen. The line is priced by scanning all 120,000 promotions. Web traffic during the rollout is only about 10% of carts, which is why the hit ratio falls to about 0.1 instead of to zero.

## Why sorting is not the fix

Sorting the codes before they enter the key collapses order, not membership. Combinations of 3–6 codes from 14 are still 6,370 lists. Each list is a separate key for every SKU, store, and tier. The hot SKUs cannot keep 6,370 variants per store and tier in a 200,000-entry cache, and a given wallet is rarely repeated. Hit ratio stays poor.

## Fix

Cache the coupon-independent unit price. Key it by SKU, store, tier, and weekday only.

At deals sync, split the book. Promotions whose programs do not mention `COUPON_PRESENTED` produce the cached price. Promotions that do mention a coupon, including the `TIER_AT_LEAST NOT COUPON_PRESENTED OR` form, stay out of that value. Index those by the coupon id they test.

On each line, take the cached price and apply only the coupon-gated promotions for the coupons actually presented, with the same stackable / non-stackable rules and the same 40% cap. A hit is then a map lookup plus a handful of rule checks. Coupon-gated promotions are about 10% of the book; they must not pull the other 90% back onto the request path.
