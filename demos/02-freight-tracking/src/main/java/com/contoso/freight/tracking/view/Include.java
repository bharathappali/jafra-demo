package com.contoso.freight.tracking.view;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum Include {
    EVENTS,
    POD;

    public static Set<Include> parse(String raw) {
        EnumSet<Include> includes = EnumSet.noneOf(Include.class);
        if (raw == null || raw.isBlank()) {
            return includes;
        }
        for (String part : raw.split(",")) {
            String token = part.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            switch (token) {
                case "events" -> includes.add(EVENTS);
                case "pod", "proof_of_delivery" -> includes.add(POD);
                default -> throw new IllegalArgumentException("include");
            }
        }
        return includes;
    }
}
