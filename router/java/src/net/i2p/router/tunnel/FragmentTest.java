package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.Date;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.util.Log;

/**
 * Simple test to see if the fragmentation is working, testing the preprocessor,
 * FragmentHandler, and FragmentedMessage operation.
 *
 */
public class FragmentTest {
    private I2PAppContext _context;
    private Log _log;
    
    public FragmentTest() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(FragmentTest.class);
    }
    
    /**
     * Send a message that fits inside a single fragment through
     *
     */
    public void runSingle() {
        DataMessage m = new DataMessage(_context);
        byte data[] = new byte[949];
        _context.random().nextBytes(data);
        m.setData(data);
        m.setUniqueId(42);
        m.setMessageExpiration(new Date(_context.clock().now() + 60*1000));
        ArrayList messages = new ArrayList();
        TunnelGateway.Pending pending = new TunnelGateway.Pending(m, null, null);
        messages.add(pending);
        
        TrivialPreprocessor pre = new TrivialPreprocessor(_context);
        SenderImpl sender = new SenderImpl();
        FragmentHandler handler = new FragmentHandler(_context, new DefragmentedReceiverImpl(m));
        ReceiverImpl receiver = new ReceiverImpl(handler, 0);
        byte msg[] = m.toByteArray();
        _log.debug("SEND(" + msg.length + "): " + Base64.encode(msg) + " " + _context.sha().calculateHash(msg).toBase64());
        pre.preprocessQueue(messages, new SenderImpl(), receiver);
    }
    
    /**
     * Send a message with two fragments through with no delay
     *
     */
    public void runMultiple() {
        DataMessage m = new DataMessage(_context);
        byte data[] = new byte[2048];
        _context.random().nextBytes(data);
        m.setData(data);
        m.setUniqueId(42);
        m.setMessageExpiration(new Date(_context.clock().now() + 60*1000));
        ArrayList messages = new ArrayList();
        TunnelGateway.Pending pending = new TunnelGateway.Pending(m, null, null);
        messages.add(pending);
        
        TrivialPreprocessor pre = new TrivialPreprocessor(_context);
        SenderImpl sender = new SenderImpl();
        FragmentHandler handler = new FragmentHandler(_context, new DefragmentedReceiverImpl(m));
        ReceiverImpl receiver = new ReceiverImpl(handler, 0);
        byte msg[] = m.toByteArray();
        _log.debug("SEND(" + msg.length + "): " + Base64.encode(msg) + " " + _context.sha().calculateHash(msg).toBase64());
        pre.preprocessQueue(messages, new SenderImpl(), receiver);
    }
    
    /**
     * Send a fragmented message, except wait a while between each fragment, causing
     * the defragmentation to fail (since the fragments will expire)
     *
     */
    public void runDelayed() {
        DataMessage m = new DataMessage(_context);
        byte data[] = new byte[2048];
        _context.random().nextBytes(data);
        m.setData(data);
        m.setUniqueId(42);
        m.setMessageExpiration(new Date(_context.clock().now() + 60*1000));
        ArrayList messages = new ArrayList();
        TunnelGateway.Pending pending = new TunnelGateway.Pending(m, null, null);
        messages.add(pending);
        
        TrivialPreprocessor pre = new TrivialPreprocessor(_context);
        SenderImpl sender = new SenderImpl();
        FragmentHandler handler = new FragmentHandler(_context, new DefragmentedReceiverImpl(m));
        ReceiverImpl receiver = new ReceiverImpl(handler, 21*1000);
        byte msg[] = m.toByteArray();
        _log.debug("SEND(" + msg.length + "): " + Base64.encode(msg) + " " + _context.sha().calculateHash(msg).toBase64());
        pre.preprocessQueue(messages, new SenderImpl(), receiver);
    }
    
    private class SenderImpl implements TunnelGateway.Sender {
        public void sendPreprocessed(byte[] preprocessed, TunnelGateway.Receiver receiver) {
            receiver.receiveEncrypted(preprocessed);
        }
    }
    private class ReceiverImpl implements TunnelGateway.Receiver {
        private FragmentHandler _handler;
        private int _delay;
        public ReceiverImpl(FragmentHandler handler, int delay) { 
            _handler = handler; 
            _delay = delay;
        }
        public void receiveEncrypted(byte[] encrypted) {
            _handler.receiveTunnelMessage(encrypted, 0, encrypted.length);
            try { Thread.sleep(_delay); } catch (Exception e) {}
        }
    }
    
    private class DefragmentedReceiverImpl implements FragmentHandler.DefragmentedReceiver {
        private I2NPMessage _expected;
        public DefragmentedReceiverImpl(I2NPMessage expected) {
            _expected = expected;
        }
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            _log.debug("equal? " + _expected.equals(msg));
        }
        
    }
    
    public static void main(String args[]) {
        FragmentTest t = new FragmentTest();
        t.runSingle();
        t.runMultiple();
        t.runDelayed();
    }
}
