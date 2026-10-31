package com.northwind.pricing.quote;

import java.util.List;
import java.util.Locale;

/**
 * Canonical form of shopper-entered coupon codes.
 */
final class CouponCodes {
    private CouponCodes() {
    }

    static List<String> normalize(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return codes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
