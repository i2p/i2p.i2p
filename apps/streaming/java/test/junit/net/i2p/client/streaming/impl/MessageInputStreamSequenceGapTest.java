package net.i2p.client.streaming.impl;

import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

public class MessageInputStreamSequenceGapTest {
    private MessageInputStream in;

    @Before
    public void setUp() {
        ConnectionOptions options = new ConnectionOptions();
        in = new MessageInputStream(I2PAppContext.getGlobalContext(), options.getMaxMessageSize(),
                                    options.getMaxWindowSize(), options.getInboundBufferSize());
    }

    @After
    public void tearDown() {
        in.close();
    }

    @Test
    public void testCanAcceptRejectsUnencodableSequenceGaps() {
        // The NACK count occupies one byte, so at most 255 holes fit.
        assertTrue(in.canAccept(255, 1));
        assertFalse(in.canAccept(256, 1));
        assertFalse(in.canAccept(0xffffffffL, 1));
        in.messageReceived(0, new ByteArray());
        assertTrue(in.canAccept(256, 1));
        assertFalse(in.canAccept(257, 1));
    }

    @Test
    public void testEmptyPacketsCannotBypassSequenceGapLimit() {
        assertTrue(in.canAccept(255, 0));
        assertFalse(in.canAccept(256, 0));
        assertFalse(in.canAccept(0xffffffffL, 0));
        in.messageReceived(255, new ByteArray());
        assertEquals(255, in.getNacks().length);
        assertTrue(in.canAccept(255, 0));
        assertTrue(in.canAccept(0, 0));
    }
}
