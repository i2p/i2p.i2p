package net.i2p.client.streaming;

import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Stress test the MessageInputStream
 */
public class MessageInputStreamTest {
    private I2PAppContext _context;
    private Log _log;
    
    public MessageInputStreamTest() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(MessageInputStreamTest.class);
    }
    
    public void testInOrder() {
        byte orig[] = new byte[32*1024];
        _context.random().nextBytes(orig);
        
        MessageInputStream in = new MessageInputStream();
        for (int i = 0; i < 32; i++) {
            byte msg[] = new byte[1024];
            System.arraycopy(orig, i*1024, msg, 0, 1024);
            in.messageReceived(i, msg);
        }
        
        byte read[] = new byte[32*1024];
        try {
            int howMany = DataHelper.read(in, read);
            if (howMany != orig.length)
                throw new RuntimeException("Failed test: not enough bytes read [" + howMany + "]");
            if (!DataHelper.eq(orig, read))
                throw new RuntimeException("Failed test: data read is not equal");
            
            _log.info("Passed test: in order");
        } catch (IOException ioe) {
            throw new RuntimeException("IOError reading: " + ioe.getMessage());
        }
    }
    
    public void testRandomOrder() {
        byte orig[] = new byte[32*1024];
        _context.random().nextBytes(orig);
        
        MessageInputStream in = new MessageInputStream();
        ArrayList order = new ArrayList(32);
        for (int i = 0; i < 32; i++)
            order.add(new Integer(i));
        Collections.shuffle(order);
        for (int i = 0; i < 32; i++) {
            byte msg[] = new byte[1024];
            Integer cur = (Integer)order.get(i);
            System.arraycopy(orig, cur.intValue()*1024, msg, 0, 1024);
            in.messageReceived(cur.intValue(), msg);
            _log.debug("Injecting " + cur);
        }
        
        byte read[] = new byte[32*1024];
        try {
            int howMany = DataHelper.read(in, read);
            if (howMany != orig.length)
                throw new RuntimeException("Failed test: not enough bytes read [" + howMany + "]");
            if (!DataHelper.eq(orig, read))
                throw new RuntimeException("Failed test: data read is not equal");
            
            _log.info("Passed test: random order");
        } catch (IOException ioe) {
            throw new RuntimeException("IOError reading: " + ioe.getMessage());
        }
    }
    
    public static void main(String args[]) {
        MessageInputStreamTest t = new MessageInputStreamTest();
        try {
            t.testInOrder();
            t.testRandomOrder();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
    }
}
