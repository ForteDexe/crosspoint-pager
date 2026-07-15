package org.crosspointreader.pagerrelay;

final class MailboxSchedule {
    private MailboxSchedule() {}

    static long currentOrNextUtcWindow(long nowWallClockMs, long intervalMs, long windowMs) {
        if (nowWallClockMs < 0L || intervalMs <= 0L || windowMs <= 0L) {
            return 0L;
        }
        long currentBoundary = nowWallClockMs - Math.floorMod(nowWallClockMs, intervalMs);
        return nowWallClockMs < currentBoundary + windowMs
                ? currentBoundary : currentBoundary + intervalMs;
    }

    static long scanDelay(long nowWallClockMs, long intervalMs, long windowMs, long scanLeadMs) {
        long windowAtMs = currentOrNextUtcWindow(nowWallClockMs, intervalMs, windowMs);
        return windowAtMs == 0L ? 0L : Math.max(0L, windowAtMs - scanLeadMs - nowWallClockMs);
    }
}
