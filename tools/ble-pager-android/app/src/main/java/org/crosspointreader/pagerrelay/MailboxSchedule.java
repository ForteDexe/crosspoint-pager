package org.crosspointreader.pagerrelay;

final class MailboxSchedule {
    private MailboxSchedule() {}

    static long afterObservedWindow(long observedAtMs, long intervalMs) {
        return intervalMs > 0L ? observedAtMs + intervalMs : 0L;
    }

    static long advancePastExpiredWindows(long nextWindowAtMs, long intervalMs,
                                          long windowMs, long nowMs) {
        if (nextWindowAtMs <= 0L || intervalMs <= 0L || windowMs <= 0L
                || nowMs < nextWindowAtMs + windowMs) {
            return nextWindowAtMs;
        }
        long elapsedAfterWindowMs = nowMs - (nextWindowAtMs + windowMs);
        long elapsedIntervals = elapsedAfterWindowMs / intervalMs + 1L;
        return nextWindowAtMs + elapsedIntervals * intervalMs;
    }
}
