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
import java.util.List;

import org.junit.Test;

import static junit.framework.TestCase.*;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;

/**
 * Quick unit test for base functionality of outbound tunnel 
 * operation
 */
public class OutboundGatewayTest extends GatewayTestBase {
    
    @Override
    protected void setupSenderAndReceiver() {
        _sender = new OutboundSender(_context, _config);
        _receiver = new TestReceiver(_config);
    }
    
    @Test
    public void testSmall() throws Exception {
    	int runCount = 1;
    	
        List messages = new ArrayList(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = getTestMessage(64);
            messages.add(m);
            _gw.add(m, null, null);
        }
        
        Thread.sleep(10000);
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    @Test
    public void testRouter() {
    	int runCount = 1;
    	
        List messages = new ArrayList(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = getTestMessage(64);
            Hash to = new Hash(new byte[Hash.HASH_LENGTH]);
            java.util.Arrays.fill(to.getData(), (byte)0xFF);
            messages.add(m);
            _gw.add(m, to, null);
        }
        
        long time = _context.clock().now() - start;
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    @Test
    public void testTunnel() {
    	int runCount = 1;
    	
        List messages = new ArrayList(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = getTestMessage(64);
            Hash to = new Hash(new byte[Hash.HASH_LENGTH]);
            java.util.Arrays.fill(to.getData(), (byte)0xFF);
            TunnelId tunnel = new TunnelId(42);
            byte data[] = m.toByteArray();
            messages.add(m);
            _gw.add(m, to, tunnel);
        }
        
        long time = _context.clock().now() - start;
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    @Test
    public void testLarge() {
    	int runCount = 1;
    	
        List messages = new ArrayList(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = getTestMessage(1024);
            messages.add(m);
            _gw.add(m, null, null);
        }
        
        long time = _context.clock().now() - start;
        //try { Thread.sleep(60*1000); } catch (Exception e) {}
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    private class TestReceiverr implements TunnelGateway.Receiver, FragmentHandler.DefragmentedReceiver {
        private TunnelCreatorConfig _config;
        private FragmentHandler _handler;
        private List _received;
        public TestReceiverr(TunnelCreatorConfig config) {
            _config = config;
            _handler = new FragmentHandler(_context, TestReceiverr.this);
            _received = new ArrayList(1000);
        }
        public long receiveEncrypted(byte[] encrypted) {
            // fake all the hops...
            
            for (int i = 1; i < _config.getLength(); i++) {
                HopProcessor hop = new HopProcessor(_context, _config.getConfig(i));
                assertTrue(hop.process(encrypted, 0, encrypted.length, _config.getConfig(i).getReceiveFrom()));
                
            }
            

            _handler.receiveTunnelMessage(encrypted, 0, encrypted.length);
            return -1; // or do we need to return the real message ID?
        }        
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            _received.add(msg);
        }
        public List clearReceived() { 
            List rv = _received; 
            _received = new ArrayList();
            return rv;
        }
        @Override
        public Hash getSendTo() {
            // TODO Auto-generated method stub
            return null;
        }
    }
}
