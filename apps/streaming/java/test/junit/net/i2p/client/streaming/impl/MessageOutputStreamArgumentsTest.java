package net.i2p.client.streaming.impl;

import java.io.ByteArrayOutputStream;
import junit.framework.TestCase;
import org.junit.Test;
import static org.junit.Assert.assertThrows;
import net.i2p.I2PAppContext;
import net.i2p.util.SimpleTimer2;

public class MessageOutputStreamArgumentsTest extends TestCase {
    private I2PAppContext _context;
    private SimpleTimer2 _st2;
    public void setUp() {
        _context = I2PAppContext.getGlobalContext();
        _st2 = _context.simpleTimer2();
    }
    @Test
    public void testInvalidWriteDoesNotSendPartialData() throws Exception {
        Receiver receiver = new Receiver();
        MessageOutputStream out = new MessageOutputStream(_context, _st2, receiver, 100, 4, 0);
        try {
            out.write(new byte[] { 42 });
            assertThrows(IndexOutOfBoundsException.class, () -> out.write(new byte[4], 0, 5));
            assertEquals(0, receiver.getData().length);
            out.flush();
            assertTrue(java.util.Arrays.equals(new byte[] { 42 }, receiver.getData()));
        } finally {
            out.closeInternal();
        }
    }

    @Test
    public void testInvalidWriteArguments() throws Exception {
        Receiver receiver = new Receiver();
        MessageOutputStream out = new MessageOutputStream(_context, _st2, receiver, 100, 4, 0);
        try {
            byte[] data = new byte[4];
            assertThrows(NullPointerException.class, () -> out.write(null, 0, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> out.write(data, -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> out.write(data, 5, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> out.write(data, 0, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> out.write(data, 1, Integer.MAX_VALUE));
            out.write(data, data.length, 0);
            assertEquals(0, receiver.getData().length);
        } finally {
            out.closeInternal();
        }
    }
    private class Receiver implements MessageOutputStream.DataReceiver {
        private ByteArrayOutputStream _data;
        public Receiver() {
            _data = new ByteArrayOutputStream();
        }
        public MessageOutputStream.WriteStatus writeData(byte[] buf, int off, int size) {
            _data.write(buf, off, size);
            return new DummyWriteStatus();
        }
        public boolean writeInProcess() { return false; }
        public byte[] getData() { return _data.toByteArray(); }
    }

    private static class DummyWriteStatus implements MessageOutputStream.WriteStatus {
        public void waitForAccept(int maxWaitMs) {}
        public void waitForCompletion(int maxWaitMs) {}
        public boolean writeAccepted() { return true; }
        public boolean writeFailed() { return false; }
        public boolean writeSuccessful() { return true; }
    }
}
