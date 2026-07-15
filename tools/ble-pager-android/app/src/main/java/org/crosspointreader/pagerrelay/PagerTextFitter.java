package org.crosspointreader.pagerrelay;

import android.graphics.Paint;
import android.graphics.Typeface;

/** Conservative phone-side fit for the Xteink Pager's two one-line fields. */
final class PagerTextFitter {
    interface WidthMeasurer {
        float measure(String text);
    }

    private static final float CONTENT_WIDTH_PX = 420f;
    private static final float TITLE_TIME_GAP_PX = 20f;
    private static final String ELLIPSIS = "...";

    private PagerTextFitter() {}

    static PagerProtocol.NotificationItem fit(String eventId, String time, String title, String message) {
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titlePaint.setTextSize(20f);
        Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        smallPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        smallPaint.setTextSize(16f);

        String safeTime = fitSingleLine(time, PagerProtocol.MAX_TIME_BYTES, CONTENT_WIDTH_PX,
                smallPaint::measureText, false);
        float titleWidth = Math.max(40f,
                CONTENT_WIDTH_PX - smallPaint.measureText(safeTime) - TITLE_TIME_GAP_PX);
        String safeTitle = fitSingleLine(
                title, PagerProtocol.MAX_TITLE_BYTES, titleWidth, titlePaint::measureText, true);
        int messageBytes = PagerProtocol.maxAddMessageBytes(safeTime, safeTitle);
        String safeMessage = fitSingleLine(
                message, messageBytes, CONTENT_WIDTH_PX, smallPaint::measureText, true);
        return new PagerProtocol.NotificationItem(eventId, safeTime, safeTitle, safeMessage);
    }

    static String fitSingleLine(String value, int maxBytes, float maxWidth,
                                WidthMeasurer measurer, boolean useEllipsis) {
        String clean = clean(value);
        if (clean.isEmpty() || maxBytes <= 0 || maxWidth <= 0f) {
            return "";
        }
        if (PagerProtocol.utf8Length(clean) <= maxBytes && measurer.measure(clean) <= maxWidth) {
            return clean;
        }

        String suffix = useEllipsis ? ELLIPSIS : "";
        if (PagerProtocol.utf8Length(suffix) > maxBytes || measurer.measure(suffix) > maxWidth) {
            return "";
        }
        StringBuilder prefix = new StringBuilder();
        String best = suffix;
        for (int offset = 0; offset < clean.length();) {
            int codePoint = clean.codePointAt(offset);
            prefix.appendCodePoint(codePoint);
            String candidate = trimTrailing(prefix.toString()) + suffix;
            if (PagerProtocol.utf8Length(candidate) > maxBytes || measurer.measure(candidate) > maxWidth) {
                break;
            }
            best = candidate;
            offset += Character.charCount(codePoint);
        }
        return best;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
    }

    private static String trimTrailing(String value) {
        int end = value.length();
        while (end > 0) {
            int codePoint = value.codePointBefore(end);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }
}
