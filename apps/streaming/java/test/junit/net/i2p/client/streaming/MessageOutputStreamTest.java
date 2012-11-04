package net.i2p.client.streaming;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.util.Log;

/**
 *
 */
public class MessageOutputStreamTest {
    private I2PAppContext _context;
    private Log _log;
    
    public MessageOutputStreamTest() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(MessageOutputStreamTest.class);
    }
    
    public void test() {
        Receiver receiver = new Receiver();
        MessageOutputStream out = new MessageOutputStream(_context, receiver);
        byte buf[] = new byte[128*1024];
        _context.random().nextBytes(buf);
        try {
            out.write(buf);
            out.flush();
        } catch (IOException ioe) { ioe.printStackTrace(); }
        byte read[] = receiver.getData();
        int firstOff = -1;
        for (int k = 0; k < buf.length; k++) {
            if (buf[k] != read[k]) {
                firstOff = k;
                break;
            }
        }
        if (firstOff < 0) {
            System.out.println("** Read match");
        } else {
            System.out.println("** Read does not match: first off = " + firstOff);
            _log.error("read does not match (first off = " + firstOff + "): \n"
                        + Base64.encode(buf) + "\n" 
                        + Base64.encode(read));
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
        public void waitForAccept(int maxWaitMs) { return; }
        public void waitForCompletion(int maxWaitMs) { return; }
        public boolean writeAccepted() { return true; }
        public boolean writeFailed() { return false; }
        public boolean writeSuccessful() { return true; }
    }
    
    public static void main(String args[]) {
        MessageOutputStreamTest t = new MessageOutputStreamTest();
        t.test();
    }
}
