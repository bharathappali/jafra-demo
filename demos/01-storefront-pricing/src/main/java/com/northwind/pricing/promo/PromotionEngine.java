package com.northwind.pricing.promo;

import com.northwind.pricing.catalog.LoyaltyTier;
import com.northwind.pricing.catalog.Product;
import com.northwind.pricing.catalog.Store;

public final class PromotionEngine {
    public static final int MAX_TOTAL_DISCOUNT_BPS = 4_000;

    private static final int[] NO_COUPONS = new int[0];

    private final PromotionCatalog catalog;

    public PromotionEngine(PromotionCatalog catalog) {
        this.catalog = catalog;
    }

    public AppliedPricing applyPromotions(
            Product product,
            Store store,
            LoyaltyTier tier,
            int[] couponIds,
            int dayOfWeek) {
        int count = catalog.promotionCount;
        int[] program = catalog.program;
        int[] programStart = catalog.programStart;
        int[] programLength = catalog.programLength;
        long[] categoryMasks = catalog.categoryMasks;
        long[] storeMasks = catalog.storeMasks;
        int[] scheduleDays = catalog.scheduleDays;
        int[] discountBps = catalog.discountBps;
        int[] amountOffCents = catalog.amountOffCents;
        boolean[] stackable = catalog.stackable;
        int[] priority = catalog.priority;

        int category = product.categoryId();
        int brand = product.brandId();
        long listPrice = product.listPriceCents();
        int tierOrdinal = tier.ordinal();
        int categoryWord = category >>> 6;
        long categoryBit = 1L << (category & 63);
        long storeBit = 1L << store.ordinal();
        int dayBit = 1 << dayOfWeek;
        int[] coupons = couponIds == null ? NO_COUPONS : couponIds;
        long zone = listPrice * store.priceZoneBps() / 10_000L;
        int[] stack = new int[16];

        long stackableDiscount = 0;
        int stackedPromotions = 0;
        long bestDiscount = -1;
        int bestPriority = -1;
        int bestPromotion = -1;

        for (int promo = 0; promo < count; promo++) {
            int start = programStart[promo];
            int length = programLength[promo];
            int sp = 0;
            for (int pc = 0; pc < length; pc++) {
                int opcode = program[start + pc];
                switch (opcode) {
                    case EligibilityRule.TIER_AT_LEAST -> {
                        int arg = program[start + (++pc)];
                        stack[sp++] = tierOrdinal >= arg ? 1 : 0;
                    }
                    case EligibilityRule.CATEGORY_IN -> {
                        long mask = categoryMasks[promo * 4 + categoryWord];
                        stack[sp++] = (mask & categoryBit) != 0 ? 1 : 0;
                    }
                    case EligibilityRule.BRAND_IS -> {
                        int arg = program[start + (++pc)];
                        stack[sp++] = brand == arg ? 1 : 0;
                    }
                    case EligibilityRule.STORE_IN -> stack[sp++] = (storeMasks[promo] & storeBit) != 0 ? 1 : 0;
                    case EligibilityRule.SCHEDULED_NOW -> stack[sp++] = (scheduleDays[promo] & dayBit) != 0 ? 1 : 0;
                    case EligibilityRule.COUPON_PRESENTED -> {
                        int arg = program[start + (++pc)];
                        stack[sp++] = presented(coupons, arg);
                    }
                    case EligibilityRule.LIST_PRICE_AT_LEAST -> {
                        int arg = program[start + (++pc)];
                        stack[sp++] = listPrice >= arg ? 1 : 0;
                    }
                    case EligibilityRule.AND -> {
                        int right = stack[--sp];
                        int left = stack[--sp];
                        stack[sp++] = left & right;
                    }
                    case EligibilityRule.OR -> {
                        int right = stack[--sp];
                        int left = stack[--sp];
                        stack[sp++] = left | right;
                    }
                    case EligibilityRule.NOT -> stack[sp - 1] = stack[sp - 1] == 0 ? 1 : 0;
                    default -> throw new IllegalStateException(
                            "Unknown eligibility opcode " + opcode + " at promotion " + promo);
                }
            }
            if (sp == 0 || stack[sp - 1] == 0) {
                continue;
            }
            long discount = 0;
            int bps = discountBps[promo];
            if (bps != 0) {
                discount = zone * bps / 10_000L;
            }
            int amount = amountOffCents[promo];
            if (amount != 0) {
                discount += amount;
            }
            if (stackable[promo]) {
                stackableDiscount += discount;
                stackedPromotions++;
            } else if (discount > bestDiscount || (discount == bestDiscount && priority[promo] > bestPriority)) {
                bestDiscount = discount;
                bestPriority = priority[promo];
                bestPromotion = promo;
            }
        }

        long totalDiscount = stackableDiscount + (bestDiscount < 0 ? 0 : bestDiscount);
        long cap = zone * MAX_TOTAL_DISCOUNT_BPS / 10_000L;
        if (totalDiscount > cap) {
            totalDiscount = cap;
        }
        return new AppliedPricing(zone, totalDiscount, bestPromotion, stackedPromotions);
    }

    private static int presented(int[] couponIds, int couponId) {
        for (int id : couponIds) {
            if (id == couponId) {
                return 1;
            }
        }
        return 0;
    }
}
