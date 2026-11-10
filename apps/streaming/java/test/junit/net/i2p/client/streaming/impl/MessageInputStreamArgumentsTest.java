package net.i2p.client.streaming.impl;

import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

public class MessageInputStreamArgumentsTest {
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
    public void testInvalidReadDoesNotConsumeData() throws IOException {
        in.messageReceived(0, new ByteArray(new byte[] { 1, 2 }));
        in.messageReceived(1, new ByteArray(new byte[] { 3, 4 }));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[3], 0, 4));
        assertEquals(4, in.available());
    }

    @Test
    public void testInvalidReadArgumentsOnEmptyStream() throws IOException {
        in.setReadTimeout(0);
        byte[] data = new byte[4];
        assertThrows(NullPointerException.class, () -> in.read(null, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(data, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(data, 5, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(data, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(data, 1, Integer.MAX_VALUE));
        assertEquals(0, in.read(data, data.length, 0));
    }
}
