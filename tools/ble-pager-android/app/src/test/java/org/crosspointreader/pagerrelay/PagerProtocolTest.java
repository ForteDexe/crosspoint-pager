package org.crosspointreader.pagerrelay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

public final class PagerProtocolTest {
    private static final String TOKEN = "0123456789abcdef";
    private static final String ID = "0011223344556677";
    private static final String DEVICE_ID = "001122334455";

    @Test
    public void oneEventBatchIsBeginAddEnd() {
        PagerProtocol.NotificationItem item = new PagerProtocol.NotificationItem(
                ID, "11:28 AM", "Title", "Body");
        List<PagerProtocol.WriteCommand> commands = PagerProtocol.notificationBatch(
                Collections.singletonList(item), 4, 1_752_595_680L);
        assertEquals(3, commands.size());
        assertEquals("BEGIN", commands.get(0).operation);
        assertEquals("ADD", commands.get(1).operation);
        assertEquals(ID, commands.get(1).eventId);
        assertEquals("END", commands.get(2).operation);
    }

    @Test
    public void notificationLimitIsBoundedToEleven() {
        List<PagerProtocol.WriteCommand> commands = PagerProtocol.notificationBatch(
                Collections.emptyList(), 12, 1_752_595_680L);

        assertTrue(commands.get(0).data.contains("\n11\n"));
    }

    @Test
    public void exactTransportBoundaryIsAccepted() {
        String data = ID + "\n" + ID + "\n" + repeat('t', 11) + "\n"
                + repeat('a', 48) + "\n" + repeat('b', 92);
        PagerProtocol.WriteCommand command = new PagerProtocol.WriteCommand("ADD", data, ID);
        assertEquals(216, PagerProtocol.utf8Length(PagerProtocol.authenticatedCommand(command, TOKEN)));
        assertTrue(PagerProtocol.isValidAuthenticatedCommand(command, TOKEN));
    }

    @Test
    public void byteBeyondTransportBoundaryIsRejected() {
        String data = ID + "\n" + ID + "\n" + repeat('t', 11) + "\n"
                + repeat('a', 48) + "\n" + repeat('b', 93);
        PagerProtocol.WriteCommand command = new PagerProtocol.WriteCommand("ADD", data, ID);
        assertEquals(217, PagerProtocol.utf8Length(PagerProtocol.authenticatedCommand(command, TOKEN)));
        assertFalse(PagerProtocol.isValidAuthenticatedCommand(command, TOKEN));
    }

    @Test
    public void shortTimeAndTitleDonateBytesToBody() {
        assertEquals(151, PagerProtocol.maxAddMessageBytes("", ""));
        assertEquals(140, PagerProtocol.maxAddMessageBytes("8:28 p.m.", "hi"));

        String data = ID + "\n" + ID + "\n\n\n" + repeat('j', 151);
        PagerProtocol.WriteCommand command = new PagerProtocol.WriteCommand("ADD", data, ID);
        assertEquals(216, PagerProtocol.utf8Length(PagerProtocol.authenticatedCommand(command, TOKEN)));
        assertTrue(PagerProtocol.isValidAuthenticatedCommand(command, TOKEN));
    }

    @Test
    public void elevenNotificationsRequireProtocolVersionEight() {
        String policy = ";model=X3;device_id=" + DEVICE_ID
                + ";availability=always;configured_availability=always"
                + ";interval_s=0;window_ms=10000;schedule=utc_grid;enrolled=1";

        assertFalse(PagerProtocol.parseStatus("v=6" + policy).usablePolicy);
        assertFalse(PagerProtocol.parseStatus("v=7" + policy).usablePolicy);
        assertTrue(PagerProtocol.parseStatus("v=8" + policy).usablePolicy);
        assertEquals(11, PagerProtocol.MAX_NOTIFICATION_COUNT);
    }

    @Test
    public void duplicateNotificationContentIgnoresEventIdAndTime() {
        PagerProtocol.NotificationItem first = new PagerProtocol.NotificationItem(
                "0011223344556677", "8:28 PM", "Title", "Body");
        PagerProtocol.NotificationItem repeated = new PagerProtocol.NotificationItem(
                "8899aabbccddeeff", "8:29 PM", "Title", "Body");
        PagerProtocol.NotificationItem changed = new PagerProtocol.NotificationItem(
                "8899aabbccddeeff", "8:29 PM", "Title", "Updated body");

        assertTrue(PagerProtocol.hasSameNotificationContent(first, repeated));
        assertFalse(PagerProtocol.hasSameNotificationContent(first, changed));
    }

    private static String repeat(char value, int count) {
        return String.join("", Collections.nCopies(count, Character.toString(value)));
    }
}
