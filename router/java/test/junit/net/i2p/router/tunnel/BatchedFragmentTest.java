package net.i2p.router.tunnel;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;

import net.i2p.I2PAppContext;

/**
 * Test the batching behavior of the preprocessor with one, two, or three 
 * messages of various sizes and settings.
 *
 */
public class BatchedFragmentTest extends FragmentTest {
    
    public void setUp() {
        super.setUp();
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
    public void testBatched() {
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
        
        assertTrue(handleReceiver.receivedOk());
    }
    
    
    /**
     * Send a small message, wait a second, then send a large message, pushing
     * the first one through immediately, with the rest of the large one passed
     * after a brief delay.
     *
     */
    public void runBatches() {
        //success += testBatched(1, false, false, 1024, false, false);
        // this takes a long fucking time
        for (int i = 1; i <= 1024; i++) {
            testBatched(i, false, false, 1024, false, false, 1024, false, false);
            testBatched(i, true, false, 1024, false, false, 1024, false, false);
            testBatched(i, true, true, 1024, false, false, 1024, false, false);
            testBatched(i, false, false, 1024, true, false, 1024, false, false);
            testBatched(i, true, false, 1024, true, false, 1024, false, false);
            testBatched(i, true, true, 1024, true, false, 1024, false, false);
            testBatched(i, false, false, 1024, true, true, 1024, false, false);
            testBatched(i, true, false, 1024, true, true, 1024, false, false);
            testBatched(i, true, true, 1024, true, true, 1024, false, false);
            
            testBatched(i, false, false, 1024, false, false, 1024, true, false);
            testBatched(i, true, false, 1024, false, false, 1024, true, false);
            testBatched(i, true, true, 1024, false, false, 1024, true, false);
            testBatched(i, false, false, 1024, true, false, 1024, true, false);
            testBatched(i, true, false, 1024, true, false, 1024, true, false);
            testBatched(i, true, true, 1024, true, false, 1024, true, false);
            testBatched(i, false, false, 1024, true, true, 1024, true, false);
            testBatched(i, true, false, 1024, true, true, 1024, true, false);
            testBatched(i, true, true, 1024, true, true, 1024, true, false);
            
            testBatched(i, false, false, 1024, false, false, 1024, true, true);
            testBatched(i, true, false, 1024, false, false, 1024, true, true);
            testBatched(i, true, true, 1024, false, false, 1024, true, true);
            testBatched(i, false, false, 1024, true, false, 1024, true, true);
            testBatched(i, true, false, 1024, true, false, 1024, true, true);
            testBatched(i, true, true, 1024, true, false, 1024, true, true);
            testBatched(i, false, false, 1024, true, true, 1024, true, true);
            testBatched(i, true, false, 1024, true, true, 1024, true, true);
            testBatched(i, true, true, 1024, true, true, 1024, true, true);
        }
    }
    
    private void testBatched(int firstSize, boolean firstRouter, boolean firstTunnel, 
                            int secondSize, boolean secondRouter, boolean secondTunnel,
                            int thirdSize, boolean thirdRouter, boolean thirdTunnel) {
        TunnelGateway.Pending pending1 = createPending(firstSize, firstRouter, firstTunnel);
        TunnelGateway.Pending pending2 = createPending(secondSize, secondRouter, secondTunnel);
        TunnelGateway.Pending pending3 = createPending(thirdSize, thirdRouter, thirdTunnel);
        
        runBatch(pending1, pending2, pending3);
    }
    
    private void runBatch(TunnelGateway.Pending pending1, TunnelGateway.Pending pending2, TunnelGateway.Pending pending3) {
        ArrayList messages = new ArrayList();
        messages.add(pending1);
        
        TunnelGateway.QueuePreprocessor pre = createPreprocessor(_context);
        SenderImpl sender = new SenderImpl();
        DefragmentedReceiverImpl handleReceiver = new DefragmentedReceiverImpl(pending1.getData(), pending2.getData(), pending3.getData());
        FragmentHandler handler = new FragmentHandler(_context, handleReceiver);
        ReceiverImpl receiver = new ReceiverImpl(handler, 0);
        byte msg[] = pending1.getData();
            
        boolean keepGoing = true;
        int added = 0;
        while (keepGoing) {
            keepGoing = pre.preprocessQueue(messages, new SenderImpl(), receiver);
            if ( (keepGoing) || ((messages.size() == 0) && (added < 2) ) ) {
                try { Thread.sleep(150); } catch (InterruptedException ie) {}

                if (added == 0) { 
                    messages.add(pending2);
                    added++;
                    keepGoing = true;
                } else if (added == 1) {
                    messages.add(pending3);
                    added++;
                    keepGoing = true;
                }
            }
        }
        
        assertTrue(handleReceiver.receivedOk());
    }
}
