package net.i2p.client.streaming.impl;

import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

public class MessageInputStreamDuplicateTest {
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
    public void testOutOfOrderDuplicateKeepsOriginalPayload() throws IOException {
        assertTrue(in.messageReceived(1, new ByteArray(new byte[] { 2, 3 })));
        assertFalse(in.messageReceived(1, new ByteArray(new byte[] { 9, 9 })));
        assertEquals(0, in.available());
        assertTrue(in.messageReceived(0, new ByteArray(new byte[] { 1 })));
        byte[] data = new byte[3];
        assertEquals(3, in.read(data));
        assertArrayEquals(new byte[] { 1, 2, 3 }, data);
    }
}
