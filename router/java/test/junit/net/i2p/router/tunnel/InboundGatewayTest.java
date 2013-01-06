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
 * Quick unit test for base functionality of inbound tunnel 
 * operation
 */
public class InboundGatewayTest extends GatewayTestBase {
    
    
    @Override
    protected void setupSenderAndReceiver() {
        _sender = new InboundSender(_context, _config.getConfig(0));
        _receiver = new InboundTestReceiver(_config);
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

        Thread.sleep(1000);
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    @Test
    public void testRouter() throws Exception{
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
        
        Thread.sleep(1000);
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    @Test
    public void testTunnel() throws Exception {
    	int runCount = 1;
    	
        List messages = new ArrayList(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = getTestMessage(64);
            Hash to = new Hash(new byte[Hash.HASH_LENGTH]);
            java.util.Arrays.fill(to.getData(), (byte)0xFF);
            TunnelId tunnel = new TunnelId(42);
            messages.add(m);
            _gw.add(m, to, tunnel);
        }
        
        Thread.sleep(1000);
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    @Test
    public void testLarge() throws Exception {
    	int runCount = 1;
    	
        List messages = new ArrayList(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = getTestMessage(1024);
            messages.add(m);
            _gw.add(m, null, null);
        }
        
        Thread.sleep(1000);
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    private class InboundTestReceiver extends TestReceiver {
        public InboundTestReceiver(TunnelCreatorConfig config) {
            super(config);
        }
        
        @Override
        protected void handleAtEndpoint(byte []encrypted) {
            // now handle it at the endpoint
            InboundEndpointProcessor end = new InboundEndpointProcessor(_context, _config);
            assertTrue(end.retrievePreprocessedData(encrypted, 0, encrypted.length, _config.getPeer(_config.getLength()-2)));
        }
    }
}
