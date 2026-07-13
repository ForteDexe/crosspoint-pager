package org.crosspointreader.pagerrelay;

import java.nio.charset.StandardCharsets;

final class PagerProtocol {
    static final String DEVICE_NAME = "CrossPoint Pager";
    static final String SERVICE_UUID = "ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001";
    static final String PAYLOAD_UUID = "ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001";
    static final int MAX_PAYLOAD_BYTES = 320;

    private PagerProtocol() {}

    static String testPayload(String title, String message, String footer) {
        return clean(title) + "\n" + clean(message) + "\n" + clean(footer);
    }

    static String notificationPayload(String title, String message, String footer) {
        String safeTitle = truncateUtf8(clean(title), 96);
        String safeFooter = truncateUtf8(clean(footer), 48);
        int messageBudget = Math.max(0, MAX_PAYLOAD_BYTES - utf8Length(safeTitle) - utf8Length(safeFooter) - 2);
        return safeTitle + "\n" + truncateUtf8(clean(message), messageBudget) + "\n" + safeFooter;
    }

    static boolean isValidTestPayload(String payload) {
        return !payload.trim().isEmpty() && utf8Length(payload) <= MAX_PAYLOAD_BYTES;
    }

    static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String truncateUtf8(String value, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int bytes = utf8Length(character);
            if (used + bytes > maxBytes) {
                break;
            }
            result.append(character);
            used += bytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }
}
