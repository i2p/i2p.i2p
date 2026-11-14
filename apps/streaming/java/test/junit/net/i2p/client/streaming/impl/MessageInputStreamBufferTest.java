package net.i2p.client.streaming.impl;

import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

public class MessageInputStreamBufferTest {
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
    public void testReadySizeAcrossPartialReadsAndReordering() throws IOException {
        in.messageReceived(1, new ByteArray(new byte[] { 0, 3, 4, 0 }, 1, 2));
        in.messageReceived(2, new ByteArray());
        assertEquals(0, in.getTotalReadySize());
        in.messageReceived(0, new ByteArray(new byte[] { 1, 2 }));
        assertEquals(4, in.available());
        assertEquals(4, in.getTotalReadySize());
        assertEquals(1, in.read());
        assertEquals(3, in.available());
        byte[] data = new byte[2];
        assertEquals(2, in.read(data));
        assertArrayEquals(new byte[] { 2, 3 }, data);
        assertEquals(1, in.getTotalReadySize());
        in.messageReceived(3, new ByteArray(new byte[] { 5 }));
        assertEquals(2, in.available());
        assertEquals(2, in.read(data));
        assertArrayEquals(new byte[] { 4, 5 }, data);
        assertEquals(0, in.getTotalReadySize());
        assertEquals(0, in.available());
    }

    @Test
    public void testCloseDiscardsReadyAndPendingData() throws IOException {
        in.messageReceived(0, new ByteArray(new byte[] { 1, 2 }));
        in.messageReceived(2, new ByteArray(new byte[] { 3, 4 }));
        in.close();
        assertEquals(0, in.getTotalReadySize());
        in.messageReceived(1, new ByteArray(new byte[] { 5 }));
        assertEquals(2, in.getHighestReadyBlockId());
        assertEquals(0, in.getTotalReadySize());
        assertThrows(IOException.class, () -> in.available());
    }
}
