package com.northwind.pricing.promo;

/**
 * Postfix opcodes for a promotion eligibility program.
 */
public final class EligibilityRule {
    public static final int TIER_AT_LEAST = 1;
    public static final int CATEGORY_IN = 2;
    public static final int BRAND_IS = 3;
    public static final int STORE_IN = 4;
    public static final int SCHEDULED_NOW = 5;
    public static final int COUPON_PRESENTED = 6;
    public static final int LIST_PRICE_AT_LEAST = 7;
    public static final int AND = 8;
    public static final int OR = 9;
    public static final int NOT = 10;

    private EligibilityRule() {
    }
}
