package org.crosspointreader.pagerrelay;

import java.nio.charset.StandardCharsets;

final class PagerProtocol {
    static final String DEVICE_NAME = "CrossPoint Pager";
    static final String SERVICE_UUID = "ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001";
    static final String PAYLOAD_UUID = "ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001";
    static final String STATUS_UUID = "ca7b0003-6f6f-4d9f-9d78-3d9c4a9ed001";
    static final int MAX_PAYLOAD_BYTES = 320;
    static final int CLIENT_TOKEN_BYTES = 16;
    static final String PAYLOAD_PREFIX = "XPAGER1\nDATA\n";
    static final int AUTH_PAYLOAD_OVERHEAD_BYTES = PAYLOAD_PREFIX.length() + CLIENT_TOKEN_BYTES + 1;
    static final int MAX_DISPLAY_PAYLOAD_BYTES = MAX_PAYLOAD_BYTES - AUTH_PAYLOAD_OVERHEAD_BYTES;

    private PagerProtocol() {}

    static String testPayload(String title, String message, String footer) {
        return clean(title) + "\n" + clean(message) + "\n" + clean(footer);
    }

    static String notificationPayload(String title, String message, String footer) {
        String safeTitle = truncateUtf8(clean(title), 96);
        String safeFooter = truncateUtf8(clean(footer), 48);
        int messageBudget = Math.max(0, MAX_DISPLAY_PAYLOAD_BYTES - utf8Length(safeTitle) - utf8Length(safeFooter) - 2);
        return safeTitle + "\n" + truncateUtf8(clean(message), messageBudget) + "\n" + safeFooter;
    }

    static boolean isValidTestPayload(String payload) {
        return !payload.trim().isEmpty() && utf8Length(payload) <= MAX_DISPLAY_PAYLOAD_BYTES;
    }

    static String authenticatedPayload(String displayPayload, String token) {
        return PAYLOAD_PREFIX + token + "\n" + displayPayload;
    }

    static boolean isValidAuthenticatedPayload(String displayPayload, String token) {
        return isValidClientToken(token)
                && displayPayload != null
                && utf8Length(authenticatedPayload(displayPayload, token)) <= MAX_PAYLOAD_BYTES;
    }

    static boolean isValidClientToken(String token) {
        if (token == null || token.length() != CLIENT_TOKEN_BYTES) {
            return false;
        }
        for (int index = 0; index < CLIENT_TOKEN_BYTES; index++) {
            char character = token.charAt(index);
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    static String enrollmentToken(String rawStatus) {
        String token = StatusFields.parse(rawStatus).value("enroll_token");
        return isValidClientToken(token) ? token : "";
    }

    static boolean isEnrolled(String rawStatus) {
        return "1".equals(StatusFields.parse(rawStatus).value("enrolled"));
    }

    static PagerStatus parseStatus(String rawStatus) {
        StatusFields fields = StatusFields.parse(rawStatus);
        return new PagerStatus("mailbox".equals(fields.value("availability")),
                "1".equals(fields.value("enrolled")),
                fields.longValue("interval_s") * 1000L,
                fields.longValue("window_ms"),
                fields.longValue("next_window_ms"));
    }

    static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    static String formatStatus(String rawStatus) {
        StatusFields status = StatusFields.parse(rawStatus);
        int intervalSeconds = status.intValue("interval_s");
        double windowSeconds = status.intValue("window_ms") / 1000.0;
        String availability = "always".equals(status.value("availability"))
                ? "Always Available"
                : "Every " + formatNumber(intervalSeconds / 60.0) + " min ("
                + formatNumber(windowSeconds) + " s receive window)";

        StringBuilder result = new StringBuilder()
                .append("Enrollment: ").append("1".equals(status.value("enrolled")) ? "enrolled" : "setup open")
                .append("\nAvailability: ").append(availability)
                .append("\nAlways available profile: ").append(titleCaseProfile(status.value("profile")))
                .append("\nBLE link: ").append("1".equals(status.value("connected")) ? "connected" : "not connected");

        String lastWrite = status.value("last_write");
        if (!lastWrite.isEmpty() && !"none".equals(lastWrite)) {
            result.append("\nLast write: ").append(lastWrite);
        }

        int nextWindowMs = status.intValue("next_window_ms");
        if (!"always".equals(status.value("availability")) && nextWindowMs > 0) {
            result.append("\nNext receive window: ")
                    .append(formatNumber(nextWindowMs / 1000.0))
                    .append(" s");
        }

        int intervalUnits = status.intValue("conn_interval_units");
        if ("1".equals(status.value("connected")) && intervalUnits > 0) {
            double intervalMs = intervalUnits * 1.25;
            int timeoutUnits = status.intValue("conn_timeout_units");
            result.append("\nNegotiated link timing: ")
                    .append(formatNumber(intervalMs))
                    .append(" ms interval, latency ")
                    .append(status.value("conn_latency"))
                    .append(", ")
                    .append(formatNumber(timeoutUnits * 10.0))
                    .append(" ms timeout");
        }

        return result.toString();
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

    private static String titleCaseProfile(String profile) {
        String safeProfile = profile == null || profile.isEmpty() ? "unknown" : profile;
        StringBuilder result = new StringBuilder();
        for (String word : safeProfile.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                result.append(word.substring(1));
            }
        }
        return result.toString();
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return Integer.toString((int) value);
        }
        String formatted = String.format(java.util.Locale.US, "%.2f", value);
        return formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    static final class PagerStatus {
        final boolean mailbox;
        final boolean enrolled;
        final long intervalMs;
        final long windowMs;
        final long nextWindowMs;

        PagerStatus(boolean mailbox, boolean enrolled, long intervalMs, long windowMs, long nextWindowMs) {
            this.mailbox = mailbox;
            this.enrolled = enrolled;
            this.intervalMs = intervalMs;
            this.windowMs = windowMs;
            this.nextWindowMs = nextWindowMs;
        }

        boolean canScheduleMailbox() {
            return mailbox && enrolled && intervalMs > 0L && windowMs > 0L;
        }
    }

    private static final class StatusFields {
        private final java.util.Map<String, String> values = new java.util.HashMap<>();

        static StatusFields parse(String rawStatus) {
            StatusFields fields = new StatusFields();
            if (rawStatus == null) {
                return fields;
            }
            for (String entry : rawStatus.split(";")) {
                if (entry.isEmpty()) {
                    continue;
                }
                int separator = entry.indexOf('=');
                if (separator < 0) {
                    fields.values.put(entry, "");
                } else {
                    fields.values.put(entry.substring(0, separator), entry.substring(separator + 1));
                }
            }
            return fields;
        }

        String value(String key) {
            String value = values.get(key);
            return value == null ? "" : value;
        }

        int intValue(String key) {
            try {
                return Integer.parseInt(value(key));
            } catch (NumberFormatException error) {
                return 0;
            }
        }

        long longValue(String key) {
            try {
                return Long.parseLong(value(key));
            } catch (NumberFormatException error) {
                return 0L;
            }
        }
    }
}
