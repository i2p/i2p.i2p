package net.i2p.client.streaming.impl;

import java.io.ByteArrayOutputStream;

import junit.framework.TestCase;

import org.junit.Before;
import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.util.SimpleTimer2;

public class MessageOutputStreamTest extends TestCase {
    private I2PAppContext _context;
    private SimpleTimer2 _st2;

    @Before
    public void setUp() {
        _context = I2PAppContext.getGlobalContext();
        _st2 = _context.simpleTimer2();
    }

    @Test
    public void test() throws Exception {
        Receiver receiver = new Receiver();
        MessageOutputStream out = new MessageOutputStream(_context, _st2, receiver, 100, 100);
        byte buf[] = new byte[128*1024];
        _context.random().nextBytes(buf);
        out.write(buf);
        out.flush();
        byte read[] = receiver.getData();
        int firstOff = -1;
        for (int k = 0; k < buf.length; k++) {
            if (buf[k] != read[k]) {
                firstOff = k;
                break;
            }
        }
        assertTrue(
                "read does not match (first off = " + firstOff + "): \n"
                        + Base64.encode(buf) + "\n" 
                        + Base64.encode(read)
                        ,
                        firstOff < 0);
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
