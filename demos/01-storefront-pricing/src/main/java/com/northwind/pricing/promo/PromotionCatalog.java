package com.northwind.pricing.promo;

import java.util.SplittableRandom;

import com.northwind.pricing.catalog.ProductCatalog;

/**
 * Columnar promotion book published by a deals sync.
 */
public final class PromotionCatalog {
    private static final int PROGRAM_SLOTS = 16;

    final int promotionCount;
    final int[] programStart;
    final int[] programLength;
    final int[] program;
    final long[] categoryMasks;
    final long[] storeMasks;
    final int[] scheduleDays;
    final int[] discountBps;
    final int[] amountOffCents;
    final boolean[] stackable;
    final int[] priority;

    private PromotionCatalog(
            int promotionCount,
            int[] programStart,
            int[] programLength,
            int[] program,
            long[] categoryMasks,
            long[] storeMasks,
            int[] scheduleDays,
            int[] discountBps,
            int[] amountOffCents,
            boolean[] stackable,
            int[] priority) {
        this.promotionCount = promotionCount;
        this.programStart = programStart;
        this.programLength = programLength;
        this.program = program;
        this.categoryMasks = categoryMasks;
        this.storeMasks = storeMasks;
        this.scheduleDays = scheduleDays;
        this.discountBps = discountBps;
        this.amountOffCents = amountOffCents;
        this.stackable = stackable;
        this.priority = priority;
    }

    public static PromotionCatalog fromDealsSync(long syncId, int promotionCount, int couponCount) {
        if (promotionCount < 1) {
            throw new IllegalArgumentException("promotionCount");
        }
        if (couponCount < 1) {
            throw new IllegalArgumentException("couponCount");
        }
        SplittableRandom random = new SplittableRandom(syncId);
        int[] programStart = new int[promotionCount];
        int[] programLength = new int[promotionCount];
        int[] program = new int[promotionCount * PROGRAM_SLOTS];
        long[] categoryMasks = new long[promotionCount * 4];
        long[] storeMasks = new long[promotionCount];
        int[] scheduleDays = new int[promotionCount];
        int[] discountBps = new int[promotionCount];
        int[] amountOffCents = new int[promotionCount];
        boolean[] stackable = new boolean[promotionCount];
        int[] priority = new int[promotionCount];

        for (int promo = 0; promo < promotionCount; promo++) {
            int categories = 1 + random.nextInt(6);
            int maskBase = promo * 4;
            for (int n = 0; n < categories; n++) {
                int category = random.nextInt(ProductCatalog.CATEGORY_COUNT);
                categoryMasks[maskBase + (category >>> 6)] |= 1L << (category & 63);
            }
            storeMasks[promo] = random.nextInt(100) < 70 ? -1L : random.nextLong();
            scheduleDays[promo] = random.nextInt(100) < 80 ? 0x7F : 1 + random.nextInt(127);

            int cursor = promo * PROGRAM_SLOTS;
            programStart[promo] = cursor;
            int end = cursor;
            program[end++] = EligibilityRule.CATEGORY_IN;
            program[end++] = EligibilityRule.STORE_IN;
            program[end++] = EligibilityRule.AND;
            program[end++] = EligibilityRule.SCHEDULED_NOW;
            program[end++] = EligibilityRule.AND;

            int kind = random.nextInt(100);
            if (kind < 18) {
                program[end++] = EligibilityRule.TIER_AT_LEAST;
                program[end++] = 1 + random.nextInt(3);
                program[end++] = EligibilityRule.AND;
            } else if (kind < 30) {
                program[end++] = EligibilityRule.BRAND_IS;
                program[end++] = random.nextInt(ProductCatalog.BRAND_COUNT);
                program[end++] = EligibilityRule.LIST_PRICE_AT_LEAST;
                program[end++] = 500 + random.nextInt(4_000);
                program[end++] = EligibilityRule.OR;
                program[end++] = EligibilityRule.AND;
            } else if (kind < 36) {
                program[end++] = EligibilityRule.COUPON_PRESENTED;
                program[end++] = 1 + random.nextInt(couponCount);
                program[end++] = EligibilityRule.AND;
            } else if (kind < 40) {
                program[end++] = EligibilityRule.TIER_AT_LEAST;
                program[end++] = 2;
                program[end++] = EligibilityRule.NOT;
                program[end++] = EligibilityRule.COUPON_PRESENTED;
                program[end++] = 1 + random.nextInt(couponCount);
                program[end++] = EligibilityRule.OR;
                program[end++] = EligibilityRule.AND;
            }
            if (end - cursor > PROGRAM_SLOTS) {
                throw new IllegalStateException("eligibility program exceeds reserved slots");
            }
            programLength[promo] = end - cursor;

            if (random.nextInt(100) < 65) {
                discountBps[promo] = 200 + random.nextInt(2_300);
            } else {
                amountOffCents[promo] = 25 + random.nextInt(1_500);
            }
            stackable[promo] = random.nextInt(100) < 25;
            priority[promo] = random.nextInt(1_000);
        }

        return new PromotionCatalog(
                promotionCount,
                programStart,
                programLength,
                program,
                categoryMasks,
                storeMasks,
                scheduleDays,
                discountBps,
                amountOffCents,
                stackable,
                priority);
    }

    public int promotionCount() {
        return promotionCount;
    }
}
