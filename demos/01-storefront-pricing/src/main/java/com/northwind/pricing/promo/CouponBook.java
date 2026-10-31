package com.northwind.pricing.promo;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CouponBook {
    private static final String[] CODES = {
            "WELCOME10",
            "SPRING-HOME",
            "PANTRY5",
            "BACK2SCHOOL",
            "GARDEN15",
            "PETPALS",
            "TECHWEEK",
            "BABY-BUNDLE",
            "OUTDOOR20",
            "KITCHEN-PRO",
            "BEAUTY8",
            "FREESHIP50",
            "LOYAL-GOLD",
            "STUDENT12",
            "MILITARY10",
            "APPONLY7",
            "BULK-SAVE",
            "CLEARANCE",
            "HOLIDAY-EARLY",
            "REFER-A-FRIEND",
            "BIRTHDAY",
            "SNACK-ATTACK",
            "AUTO-CARE",
            "TOY-DAYS"
    };

    private static final int[] NONE = new int[0];

    private final Map<String, Integer> ids;

    public CouponBook() {
        Map<String, Integer> mapped = new HashMap<>();
        for (int i = 0; i < CODES.length; i++) {
            mapped.put(CODES[i], i + 1);
        }
        this.ids = Map.copyOf(mapped);
    }

    public int size() {
        return CODES.length;
    }

    public int[] resolve(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return NONE;
        }
        int[] resolved = new int[codes.size()];
        int count = 0;
        for (String code : codes) {
            Integer id = ids.get(code);
            if (id != null) {
                resolved[count++] = id;
            }
        }
        if (count == 0) {
            return NONE;
        }
        if (count == resolved.length) {
            return resolved;
        }
        return Arrays.copyOf(resolved, count);
    }
}
