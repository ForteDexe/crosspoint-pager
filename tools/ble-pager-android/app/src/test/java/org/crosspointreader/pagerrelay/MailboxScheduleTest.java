package org.crosspointreader.pagerrelay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MailboxScheduleTest {
    private static final long INTERVAL_MS = 300_000L;
    private static final long WINDOW_MS = 2_000L;
    private static final long FIRST_WINDOW_MS = 1_000_000L;

    @Test
    public void firstWindowUsesIntervalWithoutAddingWindowDuration() {
        assertEquals(1_300_000L, MailboxSchedule.afterObservedWindow(FIRST_WINDOW_MS, INTERVAL_MS));
    }

    @Test
    public void activeWindowKeepsItsScheduledStart() {
        assertEquals(FIRST_WINDOW_MS, MailboxSchedule.advancePastExpiredWindows(
                FIRST_WINDOW_MS, INTERVAL_MS, WINDOW_MS, FIRST_WINDOW_MS + WINDOW_MS - 1L));
    }

    @Test
    public void expiredWindowAdvancesByOneStartToStartInterval() {
        assertEquals(1_300_000L, MailboxSchedule.advancePastExpiredWindows(
                FIRST_WINDOW_MS, INTERVAL_MS, WINDOW_MS, FIRST_WINDOW_MS + WINDOW_MS));
    }

    @Test
    public void multipleMissedWindowsAdvanceByWholeIntervals() {
        assertEquals(1_900_000L, MailboxSchedule.advancePastExpiredWindows(
                FIRST_WINDOW_MS, INTERVAL_MS, WINDOW_MS, 1_602_000L));
    }
}
