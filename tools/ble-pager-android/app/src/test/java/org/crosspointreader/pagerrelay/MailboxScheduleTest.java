package org.crosspointreader.pagerrelay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MailboxScheduleTest {
    private static final long FIVE_MINUTES_MS = 300_000L;
    private static final long WINDOW_MS = 2_000L;

    @Test
    public void fiveMinuteIntervalUsesNextRoundedUtcBoundary() {
        long at170320 = ((17L * 60L + 3L) * 60L + 20L) * 1000L;
        long at170500 = ((17L * 60L + 5L) * 60L) * 1000L;
        assertEquals(at170500,
                MailboxSchedule.currentOrNextUtcWindow(at170320, FIVE_MINUTES_MS, WINDOW_MS));
    }

    @Test
    public void currentWindowRemainsUsableUntilItsEnd() {
        long at170500 = ((17L * 60L + 5L) * 60L) * 1000L;
        assertEquals(at170500,
                MailboxSchedule.currentOrNextUtcWindow(at170500 + WINDOW_MS - 1L,
                        FIVE_MINUTES_MS, WINDOW_MS));
    }

    @Test
    public void connectionAtWindowEndUsesNextGlobalBoundary() {
        long at170500 = ((17L * 60L + 5L) * 60L) * 1000L;
        long at171000 = ((17L * 60L + 10L) * 60L) * 1000L;
        assertEquals(at171000,
                MailboxSchedule.currentOrNextUtcWindow(at170500 + WINDOW_MS,
                        FIVE_MINUTES_MS, WINDOW_MS));
    }

    @Test
    public void scanLeadNeverProducesNegativeDelay() {
        long at170501 = ((17L * 60L + 5L) * 60L + 1L) * 1000L;
        assertEquals(0L, MailboxSchedule.scanDelay(
                at170501, FIVE_MINUTES_MS, WINDOW_MS, 10_000L));
    }
}
