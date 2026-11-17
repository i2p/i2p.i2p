package net.i2p.client.streaming.impl;

import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

public class MessageInputStreamNullPayloadTest {
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
    public void testNullPayloadsPreserveSequence() throws IOException {
        assertTrue(in.messageReceived(0, null));
        assertTrue(in.messageReceived(2, null));
        in.messageReceived(2, null);
        assertArrayEquals(new long[] { 1 }, in.getNacks());
        assertTrue(in.messageReceived(1, new ByteArray(new byte[] { 42 })));
        assertEquals(2, in.getHighestReadyBlockId());
        assertNull(in.getNacks());
        assertEquals(1, in.available());
        assertEquals(42, in.read());
        in.closeReceived();
        assertEquals(-1, in.read());
    }
}
