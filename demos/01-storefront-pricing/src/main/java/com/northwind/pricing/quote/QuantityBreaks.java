package com.northwind.pricing.quote;

final class QuantityBreaks {
    private QuantityBreaks() {
    }

    static long lineTotal(long unitCents, int quantity) {
        long extended = unitCents * (long) quantity;
        if (quantity >= 12) {
            return extended * 90 / 100;
        }
        if (quantity >= 6) {
            return extended * 95 / 100;
        }
        return extended;
    }
}
