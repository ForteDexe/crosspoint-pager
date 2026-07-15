package org.crosspointreader.pagerrelay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PagerTextFitterTest {
    @Test
    public void widthOverflowEndsWithEllipsis() {
        String fitted = PagerTextFitter.fitSingleLine(
                "abcdefghij", 48, 7f, String::length, true);
        assertEquals("abcd...", fitted);
    }

    @Test
    public void utf8BudgetDoesNotSplitEmoji() {
        String fitted = PagerTextFitter.fitSingleLine(
                "ab😀cd", 7, 100f, String::length, true);
        assertEquals("ab...", fitted);
        assertTrue(PagerProtocol.utf8Length(fitted) <= 7);
    }

    @Test
    public void fittingTextIsUnchanged() {
        assertEquals("Pager", PagerTextFitter.fitSingleLine(
                "Pager", 48, 20f, String::length, true));
    }
}
