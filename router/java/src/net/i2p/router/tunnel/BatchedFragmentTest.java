package net.i2p.router.tunnel;

import java.util.ArrayList;
import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.util.Log;

/**
 * Test the batching behavior of the preprocessor with one, two, or three 
 * messages of various sizes and settings.
 *
 */
public class BatchedFragmentTest extends FragmentTest {
    
    public BatchedFragmentTest() {
        super();
        BatchedPreprocessor.DEFAULT_DELAY = 200;
    }
    
    protected TunnelGateway.QueuePreprocessor createPreprocessor(I2PAppContext ctx) {
        return new BatchedPreprocessor(ctx);
    }
    
    /**
     * Send a small message, wait a second, then send a large message, pushing
     * the first one through immediately, with the rest of the large one passed
     * after a brief delay.
     *
     */
    public void runBatched() {
        TunnelGateway.Pending pending1 = createPending(10, false, false);
        ArrayList messages = new ArrayList();
        messages.add(pending1);
        
        TunnelGateway.Pending pending2 = createPending(1024, false, false);
        
        TunnelGateway.QueuePreprocessor pre = createPreprocessor(_context);
        SenderImpl sender = new SenderImpl();
        DefragmentedReceiverImpl handleReceiver = new DefragmentedReceiverImpl(pending1.getData(), pending2.getData());
        FragmentHandler handler = new FragmentHandler(_context, handleReceiver);
        ReceiverImpl receiver = new ReceiverImpl(handler, 0);
        byte msg[] = pending1.getData();
        _log.debug("SEND(" + msg.length + "): " + Base64.encode(msg) + " " + _context.sha().calculateHash(msg).toBase64());
            
        boolean keepGoing = true;
        boolean alreadyAdded = false;
        while (keepGoing) {
            keepGoing = pre.preprocessQueue(messages, new SenderImpl(), receiver);
            if (keepGoing) {
                try { Thread.sleep(150); } catch (InterruptedException ie) {}

                if (!alreadyAdded) { 
                    messages.add(pending2);
                    alreadyAdded = true;
                }
            }
        }
        
        if (handleReceiver.receivedOk())
            _log.info("Receive batched ok");
        else
            _log.info("Failed to receive batched");
    }
    
    
    /**
     * Send a small message, wait a second, then send a large message, pushing
     * the first one through immediately, with the rest of the large one passed
     * after a brief delay.
     *
     */
    public void runBatches() {
        int success = 0;
        //success += testBatched(1, false, false, 1024, false, false);
        // this takes a long fucking time
        for (int i = 1; i <= 1024; i++) {
            success += testBatched(i, false, false, 1024, false, false, 1024, false, false);
            success += testBatched(i, true, false, 1024, false, false, 1024, false, false);
            success += testBatched(i, true, true, 1024, false, false, 1024, false, false);
            success += testBatched(i, false, false, 1024, true, false, 1024, false, false);
            success += testBatched(i, true, false, 1024, true, false, 1024, false, false);
            success += testBatched(i, true, true, 1024, true, false, 1024, false, false);
            success += testBatched(i, false, false, 1024, true, true, 1024, false, false);
            success += testBatched(i, true, false, 1024, true, true, 1024, false, false);
            success += testBatched(i, true, true, 1024, true, true, 1024, false, false);
            
            success += testBatched(i, false, false, 1024, false, false, 1024, true, false);
            success += testBatched(i, true, false, 1024, false, false, 1024, true, false);
            success += testBatched(i, true, true, 1024, false, false, 1024, true, false);
            success += testBatched(i, false, false, 1024, true, false, 1024, true, false);
            success += testBatched(i, true, false, 1024, true, false, 1024, true, false);
            success += testBatched(i, true, true, 1024, true, false, 1024, true, false);
            success += testBatched(i, false, false, 1024, true, true, 1024, true, false);
            success += testBatched(i, true, false, 1024, true, true, 1024, true, false);
            success += testBatched(i, true, true, 1024, true, true, 1024, true, false);
            
            success += testBatched(i, false, false, 1024, false, false, 1024, true, true);
            success += testBatched(i, true, false, 1024, false, false, 1024, true, true);
            success += testBatched(i, true, true, 1024, false, false, 1024, true, true);
            success += testBatched(i, false, false, 1024, true, false, 1024, true, true);
            success += testBatched(i, true, false, 1024, true, false, 1024, true, true);
            success += testBatched(i, true, true, 1024, true, false, 1024, true, true);
            success += testBatched(i, false, false, 1024, true, true, 1024, true, true);
            success += testBatched(i, true, false, 1024, true, true, 1024, true, true);
            success += testBatched(i, true, true, 1024, true, true, 1024, true, true);
        }
        
        _log.info("** Batches complete with " + success + " successful runs");
    }
    
    private int testBatched(int firstSize, boolean firstRouter, boolean firstTunnel, 
                            int secondSize, boolean secondRouter, boolean secondTunnel,
                            int thirdSize, boolean thirdRouter, boolean thirdTunnel) {
        TunnelGateway.Pending pending1 = createPending(firstSize, firstRouter, firstTunnel);
        TunnelGateway.Pending pending2 = createPending(secondSize, secondRouter, secondTunnel);
        TunnelGateway.Pending pending3 = createPending(thirdSize, thirdRouter, thirdTunnel);
        
        boolean ok = runBatch(pending1, pending2, pending3);
        if (ok) {
            _log.info("OK: " + firstSize + "." + firstRouter + "." + firstTunnel
                      + " " + secondSize + "." + secondRouter + "." + secondTunnel
                      + " " + thirdSize + "." + thirdRouter + "." + thirdTunnel);
            return 1;
        } else {
            _log.info("FAIL: " + firstSize + "." + firstRouter + "." + firstTunnel
                      + " " + secondSize + "." + secondRouter + "." + secondTunnel
                      + " " + thirdSize + "." + thirdRouter + "." + thirdTunnel);
            return 0;
        }
    }
    
    private boolean runBatch(TunnelGateway.Pending pending1, TunnelGateway.Pending pending2, TunnelGateway.Pending pending3) {
        ArrayList messages = new ArrayList();
        messages.add(pending1);
        
        TunnelGateway.QueuePreprocessor pre = createPreprocessor(_context);
        SenderImpl sender = new SenderImpl();
        DefragmentedReceiverImpl handleReceiver = new DefragmentedReceiverImpl(pending1.getData(), pending2.getData(), pending3.getData());
        FragmentHandler handler = new FragmentHandler(_context, handleReceiver);
        ReceiverImpl receiver = new ReceiverImpl(handler, 0);
        byte msg[] = pending1.getData();
        _log.debug("SEND(" + msg.length + "): " + Base64.encode(msg) + " " + _context.sha().calculateHash(msg).toBase64());
            
        boolean keepGoing = true;
        int added = 0;
        while (keepGoing) {
            keepGoing = pre.preprocessQueue(messages, new SenderImpl(), receiver);
            if ( (keepGoing) || ((messages.size() == 0) && (added < 2) ) ) {
                try { Thread.sleep(150); } catch (InterruptedException ie) {}

                if (added == 0) { 
                    _log.debug("Adding pending2");
                    messages.add(pending2);
                    added++;
                    keepGoing = true;
                } else if (added == 1) {
                    _log.debug("Adding pending3");
                    messages.add(pending3);
                    added++;
                    keepGoing = true;
                }
            }
        }
        
        return handleReceiver.receivedOk();
    }
    
    
    public void runTests() {
        //super.runVaried();
        //super.runTests();
        //runBatched();
        runBatches();
    }
    
    public static void main(String args[]) {
        BatchedFragmentTest t = new BatchedFragmentTest();
        t.runTests();
    }
}
