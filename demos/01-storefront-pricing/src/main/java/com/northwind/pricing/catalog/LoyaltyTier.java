package com.northwind.pricing.catalog;

import java.util.Locale;

public enum LoyaltyTier {
    GUEST,
    SILVER,
    GOLD,
    PLATINUM;

    public static LoyaltyTier parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return GUEST;
        }
        return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
