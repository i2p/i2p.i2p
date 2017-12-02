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

import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

import net.i2p.router.RouterContext;

/**
 * Test the batching behavior of the preprocessor with one, two, or three 
 * messages of various sizes and settings.
 *
 */
public class BatchedFragmentTest extends FragmentTest {
    
    @Before
    public void setUp() {
        BatchedPreprocessor.DEFAULT_DELAY = 200;
    }
    
    protected TunnelGateway.QueuePreprocessor createPreprocessor(RouterContext ctx) {
        return new BatchedPreprocessor(ctx, "testBatchedPreprocessor");
    }
    
    /**
     * Send a small message, wait a second, then send a large message, pushing
     * the first one through immediately, with the rest of the large one passed
     * after a brief delay.
     *
     */
    @Test
    public void testBatched() {
        PendingGatewayMessage pending1 = createPending(10, false, false);
        ArrayList<PendingGatewayMessage> messages = new ArrayList<PendingGatewayMessage>();
        messages.add(pending1);
        
        PendingGatewayMessage pending2 = createPending(1024, false, false);
        
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
     * Send a message that fits inside a single fragment through
     *
     */
    @Test
    public void testSingle() {
        PendingGatewayMessage pending = createPending(949, false, false);
        ArrayList<PendingGatewayMessage> messages = new ArrayList<PendingGatewayMessage>();
        messages.add(pending);

        TunnelGateway.QueuePreprocessor pre = createPreprocessor(_context);
        SenderImpl sender = new SenderImpl();
        DefragmentedReceiverImpl handleReceiver = new DefragmentedReceiverImpl(pending.getData());
        FragmentHandler handler = new FragmentHandler(_context, handleReceiver);
        ReceiverImpl receiver = new ReceiverImpl(handler, 0);
        byte msg[] = pending.getData();
        
        boolean keepGoing = true;
        while (keepGoing) {
            keepGoing = pre.preprocessQueue(messages, new SenderImpl(), receiver);
            if (keepGoing)
                try { Thread.sleep(100); } catch (InterruptedException ie) {}
        }
        assertTrue(handleReceiver.receivedOk());
    }
    
    /**
     * Send a message with two fragments through with no delay
     *
     */
    @Test
    public void testMultiple() throws Exception {
        PendingGatewayMessage pending = createPending(2048, false, false);
        ArrayList<PendingGatewayMessage> messages = new ArrayList<PendingGatewayMessage>();
        messages.add(pending);
        
        TunnelGateway.QueuePreprocessor pre = createPreprocessor(_context);
        SenderImpl sender = new SenderImpl();
        DefragmentedReceiverImpl handleReceiver = new DefragmentedReceiverImpl(pending.getData());
        FragmentHandler handler = new FragmentHandler(_context, handleReceiver);
        ReceiverImpl receiver = new ReceiverImpl(handler, 0);
        byte msg[] = pending.getData();
            
        boolean keepGoing = true;
        while (keepGoing) {
            keepGoing = pre.preprocessQueue(messages, new SenderImpl(), receiver);
            if (keepGoing)
                try { Thread.sleep(100); } catch (InterruptedException ie) {}
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
        // this takes a long long time
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
        PendingGatewayMessage pending1 = createPending(firstSize, firstRouter, firstTunnel);
        PendingGatewayMessage pending2 = createPending(secondSize, secondRouter, secondTunnel);
        PendingGatewayMessage pending3 = createPending(thirdSize, thirdRouter, thirdTunnel);
        
        runBatch(pending1, pending2, pending3);
    }
    
    private void runBatch(PendingGatewayMessage pending1, PendingGatewayMessage pending2, PendingGatewayMessage pending3) {
        ArrayList<PendingGatewayMessage> messages = new ArrayList<PendingGatewayMessage>();
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
