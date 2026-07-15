package org.crosspointreader.pagerrelay;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
    static final int MAX_NOTIFICATION_COUNT = 10;
    private static final String NOTIFICATION_STACK_PREFIX = "XPSTACK1\n";

    private PagerProtocol() {}

    static String testPayload(String title, String message, String footer) {
        return clean(title) + "\n" + clean(message) + "\n" + clean(footer);
    }

    static String policyConfirmationPayload() {
        return testPayload("Pager connection confirmed", "", "");
    }

    static String notificationPayload(String title, String message, String footer) {
        String safeTitle = truncateUtf8(clean(title), 96);
        String safeFooter = truncateUtf8(clean(footer), 48);
        int messageBudget = Math.max(0, MAX_DISPLAY_PAYLOAD_BYTES - utf8Length(safeTitle) - utf8Length(safeFooter) - 2);
        return safeTitle + "\n" + truncateUtf8(clean(message), messageBudget) + "\n" + safeFooter;
    }

    static String notificationStackPayload(List<NotificationItem> notifications) {
        int count = Math.min(notifications == null ? 0 : notifications.size(), MAX_NOTIFICATION_COUNT);
        StringBuilder payload = new StringBuilder(NOTIFICATION_STACK_PREFIX);
        for (int index = 0; index < count; index++) {
            NotificationItem item = notifications.get(index);
            int entriesRemaining = count - index;
            int bytesRemaining = MAX_DISPLAY_PAYLOAD_BYTES - utf8Length(payload.toString());
            int lineBudget = Math.max(0, bytesRemaining / entriesRemaining);
            boolean hasFollowingEntry = index + 1 < count;
            int delimiterBytes = 2 + (hasFollowingEntry ? 1 : 0);

            String time = truncateUtf8(cleanField(item.time), Math.min(16, Math.max(0, lineBudget - delimiterBytes)));
            int contentBudget = Math.max(0, lineBudget - utf8Length(time) - delimiterBytes);
            int titleBudget = contentBudget * 3 / 5;
            String title = truncateUtf8(cleanField(item.title), titleBudget);
            String message = truncateUtf8(cleanField(item.message), contentBudget - utf8Length(title));

            payload.append(time).append('\t').append(title).append('\t').append(message);
            if (hasFollowingEntry) {
                payload.append('\n');
            }
        }
        return payload.toString();
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
        return isHexValue(token, CLIENT_TOKEN_BYTES);
    }

    static boolean isValidDeviceId(String deviceId) {
        return isHexValue(deviceId, 12);
    }

    private static boolean isHexValue(String value, int expectedLength) {
        if (value == null || value.length() != expectedLength) {
            return false;
        }
        for (int index = 0; index < expectedLength; index++) {
            char character = value.charAt(index);
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
        String availability = fields.value("availability");
        String configuredAvailability = fields.value("configured_availability");
        if (configuredAvailability.isEmpty()) {
            configuredAvailability = availability;
        }
        long intervalMs = fields.longValue("interval_s") * 1000L;
        long windowMs = fields.longValue("window_ms");
        boolean configuredMailbox = "mailbox".equals(configuredAvailability);
        boolean recognizedAvailability = "always".equals(availability) || "mailbox".equals(availability);
        boolean recognizedConfiguredAvailability = "always".equals(configuredAvailability) || configuredMailbox;
        boolean usablePolicy = ("X3".equals(fields.value("model")) || "X4".equals(fields.value("model")))
                && isValidDeviceId(fields.value("device_id"))
                && recognizedAvailability
                && recognizedConfiguredAvailability
                && (!configuredMailbox || intervalMs > 0L && windowMs > 0L);
        return new PagerStatus(fields.value("model"), fields.value("device_id"),
                "mailbox".equals(availability), configuredMailbox,
                "1".equals(fields.value("enrolled")),
                intervalMs, windowMs, fields.longValue("next_window_ms"), usablePolicy);
    }

    static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    static String formatStatus(String rawStatus) {
        StatusFields status = StatusFields.parse(rawStatus);
        PagerStatus pagerStatus = parseStatus(rawStatus);
        int intervalSeconds = status.intValue("interval_s");
        double windowSeconds = status.intValue("window_ms") / 1000.0;
        String effectiveAvailabilityValue = status.value("availability");
        String effectiveAvailability = "always".equals(effectiveAvailabilityValue)
                ? "Always Available"
                : "Every " + formatNumber(intervalSeconds / 60.0) + " min ("
                + formatNumber(windowSeconds) + " s receive window)";
        String configuredAvailabilityValue = status.value("configured_availability");
        if (configuredAvailabilityValue.isEmpty()) {
            configuredAvailabilityValue = status.value("availability");
        }
        String configuredAvailability = "always".equals(configuredAvailabilityValue)
                ? "Always Available"
                : "Every " + formatNumber(intervalSeconds / 60.0) + " min";

        StringBuilder result = new StringBuilder()
                .append("Device: ").append(deviceLabel(pagerStatus))
                .append("\nEnrollment: ").append("1".equals(status.value("enrolled")) ? "enrolled" : "setup open")
                .append("\nAvailability: ").append(configuredAvailability);
        if (!configuredAvailabilityValue.equals(effectiveAvailabilityValue)) {
            result.append("\nEnrollment access: temporarily ").append(effectiveAvailability);
        }
        String profile = status.value("profile");
        if ("always".equals(configuredAvailabilityValue) && !profile.isEmpty()) {
            result.append("\nAlways available profile: ").append(titleCaseProfile(profile));
        }
        int nextWindowMs = status.intValue("next_window_ms");
        if (!"always".equals(effectiveAvailabilityValue) && nextWindowMs > 0) {
            result.append("\nNext receive window: ")
                    .append(formatNumber(nextWindowMs / 1000.0))
                    .append(" s");
        }

        return result.toString();
    }

    static String formatTechnicalStatus(String rawStatus) {
        StatusFields status = StatusFields.parse(rawStatus);
        StringBuilder result = new StringBuilder()
                .append("Protocol: v").append(status.value("v"))
                .append("\nBLE link: ").append("1".equals(status.value("connected")) ? "connected" : "not connected");
        String lastWrite = status.value("last_write");
        if (!lastWrite.isEmpty() && !"none".equals(lastWrite)) {
            result.append("\nLast write: ").append(lastWrite);
        }

        int intervalUnits = status.intValue("conn_interval_units");
        if ("1".equals(status.value("connected")) && intervalUnits > 0) {
            double intervalMs = intervalUnits * 1.25;
            int timeoutUnits = status.intValue("conn_timeout_units");
            String timingLabel = "mailbox".equals(status.value("availability"))
                    ? "Mailbox link timing: "
                    : "Negotiated link timing: ";
            result.append("\n").append(timingLabel)
                    .append(formatNumber(intervalMs))
                    .append(" ms interval, latency ")
                    .append(status.value("conn_latency"))
                    .append(", ")
                    .append(formatNumber(timeoutUnits * 10.0))
                    .append(" ms timeout");
        }

        return result.toString();
    }

    static boolean wasLastWriteAccepted(String rawStatus) {
        String lastWrite = StatusFields.parse(rawStatus).value("last_write");
        return "accepted".equals(lastWrite) || "unchanged".equals(lastWrite) || "enrolled".equals(lastWrite);
    }

    static boolean wasLastWriteUnchanged(String rawStatus) {
        return "unchanged".equals(StatusFields.parse(rawStatus).value("last_write"));
    }

    static String deviceLabel(PagerStatus status) {
        String model = "X3".equals(status.model) || "X4".equals(status.model) ? " " + status.model : "";
        if (!isValidDeviceId(status.deviceId)) {
            return "Xteink" + model;
        }
        return "Xteink" + model + " · " + status.deviceId.substring(status.deviceId.length() - 6).toUpperCase();
    }

    static String availabilityLabel(boolean configuredMailbox, long intervalMs) {
        if (!configuredMailbox) {
            return "Always Available";
        }
        if (intervalMs <= 0L) {
            return "Periodic";
        }
        return "Every " + formatNumber(intervalMs / 60_000.0) + " min";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String cleanField(String value) {
        return clean(value).replace('\t', ' ');
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

    static final class NotificationItem {
        final String time;
        final String title;
        final String message;

        NotificationItem(String time, String title, String message) {
            this.time = time;
            this.title = title;
            this.message = message;
        }
    }

    static final class PagerStatus {
        final String model;
        final String deviceId;
        final boolean mailbox;
        final boolean configuredMailbox;
        final boolean enrolled;
        final long intervalMs;
        final long windowMs;
        final long nextWindowMs;
        final boolean usablePolicy;

        PagerStatus(String model, String deviceId, boolean mailbox, boolean configuredMailbox, boolean enrolled,
                    long intervalMs, long windowMs, long nextWindowMs, boolean usablePolicy) {
            this.model = model;
            this.deviceId = deviceId;
            this.mailbox = mailbox;
            this.configuredMailbox = configuredMailbox;
            this.enrolled = enrolled;
            this.intervalMs = intervalMs;
            this.windowMs = windowMs;
            this.nextWindowMs = nextWindowMs;
            this.usablePolicy = usablePolicy;
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
