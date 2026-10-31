package com.contoso.freight.tracking.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Cursors {
    private Cursors() {
    }

    public static String encode(int offset) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    public static int decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        final byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(cursor.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cursor");
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        if (!text.startsWith("o:")) {
            throw new IllegalArgumentException("cursor");
        }
        try {
            int offset = Integer.parseInt(text.substring(2));
            if (offset < 0) {
                throw new IllegalArgumentException("cursor");
            }
            return offset;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("cursor");
        }
    }
}
