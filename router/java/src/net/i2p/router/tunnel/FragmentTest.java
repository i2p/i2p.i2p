package net.i2p.router.tunnel;

import java.util.ArrayList;
import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.util.Log;

/**
 * Simple test to see if the fragmentation is working, testing the preprocessor,
 * FragmentHandler, and FragmentedMessage operation.
 *
 */
public class FragmentTest {
    protected I2PAppContext _context;
    protected Log _log;
    
    public FragmentTest() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(getClass());
        _context.random().nextBoolean();
        FragmentHandler.MAX_DEFRAGMENT_TIME = 10*1000;
    }
    
    protected TunnelGateway.QueuePreprocessor createPreprocessor(I2PAppContext ctx) {
        return new TrivialPreprocessor(ctx);
    }
    
    /**
     * Send a message that fits inside a single fragment through
     *
     */
    public void runSingle() {
        TunnelGateway.Pending pending = createPending(949, false, false);
        ArrayList messages = new ArrayList();
        messages.add(pending);

        TunnelGateway.QueuePreprocessor pre = createPreprocessor(_context);
        SenderImpl sender = new SenderImpl();
        DefragmentedReceiverImpl handleReceiver = new DefragmentedReceiverImpl(pending.getData());
        FragmentHandler handler = new FragmentHandler(_context, handleReceiver);
        ReceiverImpl receiver = new ReceiverImpl(handler, 0);
        byte msg[] = pending.getData();
        _log.debug("SEND(" + msg.length + "): " + Base64.encode(msg) + " " + _context.sha().calculateHash(msg).toBase64());

        boolean keepGoing = true;
        while (keepGoing) {
            keepGoing = pre.preprocessQueue(messages, new SenderImpl(), receiver);
            if (keepGoing)
                try { Thread.sleep(100); } catch (InterruptedException ie) {}
        }
        if (handleReceiver.receivedOk()) 
            _log.info("received OK");
    }
    
    /**
     * Send a message with two fragments through with no delay
     *
     */
    public void runMultiple() {
        TunnelGateway.Pending pending = createPending(2048, false, false);
        ArrayList messages = new ArrayList();
        messages.add(pending);
        
        TunnelGateway.QueuePreprocessor pre = createPreprocessor(_context);
        SenderImpl sender = new SenderImpl();
        DefragmentedReceiverImpl handleReceiver = new DefragmentedReceiverImpl(pending.getData());
        FragmentHandler handler = new FragmentHandler(_context, handleReceiver);
        ReceiverImpl receiver = new ReceiverImpl(handler, 0);
        byte msg[] = pending.getData();
        _log.debug("SEND(" + msg.length + "): " + Base64.encode(msg) + " " + _context.sha().calculateHash(msg).toBase64());
            
        boolean keepGoing = true;
        while (keepGoing) {
            keepGoing = pre.preprocessQueue(messages, new SenderImpl(), receiver);
            if (keepGoing)
                try { Thread.sleep(100); } catch (InterruptedException ie) {}
        }
        if (handleReceiver.receivedOk()) 
            _log.info("received OK");
    }
    
    /**
     * Send a fragmented message, except wait a while between each fragment, causing
     * the defragmentation to fail (since the fragments will expire)
     *
     */
    public void runDelayed() {
        TunnelGateway.Pending pending = createPending(2048, false, false);
        ArrayList messages = new ArrayList();
        messages.add(pending);
        TunnelGateway.QueuePreprocessor pre = createPreprocessor(_context);
        SenderImpl sender = new SenderImpl();
        FragmentHandler handler = new FragmentHandler(_context, new DefragmentedReceiverImpl(pending.getData()));
        ReceiverImpl receiver = new ReceiverImpl(handler, 11*1000);
        byte msg[] = pending.getData();
        _log.debug("SEND(" + msg.length + "): " + Base64.encode(msg) + " " + _context.sha().calculateHash(msg).toBase64());
        boolean keepGoing = true;
        while (keepGoing) {
            keepGoing = pre.preprocessQueue(messages, new SenderImpl(), receiver);
            if (keepGoing)
                try { Thread.sleep(100); } catch (InterruptedException ie) {}
        }
    }
    
    public void runVaried() {
        int failures = 0;
        for (int i = 0; i <= 4096; i++) {
            boolean ok = runVaried(i, false, false);
            if (!ok) { _log.error("** processing " + i+ " w/ no router, no tunnel failed"); failures++; }
            ok = runVaried(i, true, false);
            if (!ok) { _log.error("** processing " + i+ " w/ router, no tunnel failed"); failures++; }
            ok = runVaried(i, true, true);
            if (!ok) { _log.error("** processing " + i+ " w/ router, tunnel failed"); failures++; }
            else _log.info("Tests pass for size " + i);
        }
        if (failures == 0) 
            _log.info("** success after all varied tests");
        else
            _log.error("** failed " + failures +" varied tests");
    }
    
    protected boolean runVaried(int size, boolean includeRouter, boolean includeTunnel) {
        TunnelGateway.Pending pending = createPending(size, includeRouter, includeTunnel);
        ArrayList messages = new ArrayList();
        messages.add(pending);
        
        DefragmentedReceiverImpl handleReceiver = new DefragmentedReceiverImpl(pending.getData());
        TunnelGateway.QueuePreprocessor pre = createPreprocessor(_context);
        SenderImpl sender = new SenderImpl();
        FragmentHandler handler = new FragmentHandler(_context, handleReceiver);
        ReceiverImpl receiver = new ReceiverImpl(handler, 0);
        byte msg[] = pending.getData();
        _log.debug("SEND(" + msg.length + "): " + Base64.encode(msg) + " " + _context.sha().calculateHash(msg).toBase64());
            
        boolean keepGoing = true;
        while (keepGoing) {
            keepGoing = pre.preprocessQueue(messages, new SenderImpl(), receiver);
            if (keepGoing)
                try { Thread.sleep(100); } catch (InterruptedException ie) {}
        }
        
        return handleReceiver.receivedOk();
    }
    
    protected TunnelGateway.Pending createPending(int size, boolean includeRouter, boolean includeTunnel) {
        DataMessage m = new DataMessage(_context);
        byte data[] = new byte[size];
        _context.random().nextBytes(data);
        m.setData(data);
        m.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
        m.setMessageExpiration(_context.clock().now() + 60*1000);
        
        Hash toRouter = null;
        TunnelId toTunnel = null;
        if (includeRouter) {
            toRouter = new Hash(new byte[Hash.HASH_LENGTH]);
            _context.random().nextBytes(toRouter.getData());
        }
        if (includeTunnel)
            toTunnel = new TunnelId(_context.random().nextLong(TunnelId.MAX_ID_VALUE));
        return new TunnelGateway.Pending(m, toRouter, toTunnel);
    }
    
    protected class SenderImpl implements TunnelGateway.Sender {
        public void sendPreprocessed(byte[] preprocessed, TunnelGateway.Receiver receiver) {
            receiver.receiveEncrypted(preprocessed);
        }
    }
    protected class ReceiverImpl implements TunnelGateway.Receiver {
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
    
    protected class DefragmentedReceiverImpl implements FragmentHandler.DefragmentedReceiver {
        private byte _expected[];
        private byte _expected2[];
        private byte _expected3[];
        private int _received;
        public DefragmentedReceiverImpl(byte expected[]) {
            this(expected, null);
        }
        public DefragmentedReceiverImpl(byte expected[], byte expected2[]) {
            this(expected, expected2, null);
        }
        public DefragmentedReceiverImpl(byte expected[], byte expected2[], byte expected3[]) {
            _expected = expected;
            _expected2 = expected2;
            _expected3 = expected3;
            _received = 0;
        }
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            boolean ok = false;
            byte m[] = msg.toByteArray();
            if ( (_expected != null) && (DataHelper.eq(_expected, m)) )
                ok = true;
            if (!ok && (_expected2 != null) && (DataHelper.eq(_expected2, m)) )
                ok = true;
            if (!ok && (_expected3 != null) && (DataHelper.eq(_expected3, m)) )
                ok = true;
            if (ok)
                _received++;
            //_log.info("** equal? " + ok);
        }
        
        public boolean receivedOk() {
            if ( (_expected != null) && (_expected2 != null) && (_expected3 != null) ) 
                return _received == 3;
            else if ( (_expected != null) && (_expected2 != null) )
                return _received == 2;
            else if ( (_expected != null) || (_expected2 != null) )
                return _received == 1;
            else
                return _received == 0;
        }
    }
    
    public void runTests() {
        runVaried();
        _log.info("\n===========================Begin runSingle()\n\n");
        runSingle();
        _log.info("\n===========================Begin runMultiple()\n\n");
        runMultiple();
        _log.info("\n===========================Begin runDelayed() (should have 3 errors)\n\n");
        runDelayed();
        _log.info("\n===========================After runDelayed()\n\n");
    }
    
    public static void main(String args[]) {
        FragmentTest t = new FragmentTest();
        t.runTests();
    }
}
