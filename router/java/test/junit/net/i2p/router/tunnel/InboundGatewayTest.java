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

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static junit.framework.TestCase.*;
import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;

/**
 * Quick unit test for base functionality of inbound tunnel 
 * operation
 */
public class InboundGatewayTest {
    private static RouterContext _context;
    private static TunnelGatewayPumper _pumper;
    private TunnelCreatorConfig _config;
    private TunnelGateway.QueuePreprocessor _preprocessor;
    private TunnelGateway.Sender _sender;
    private TestReceiver _receiver;
    private TunnelGateway _gw;
    
    @BeforeClass
    public static void globalSetUp() {
        // order of these matters
        Router r = new Router();
        _context = new RouterContext(r);
        _context.initAll();
        r.runRouter();
        RouterIdentity rIdentity = new TestRouterIdentity();
        RouterInfo rInfo = new RouterInfo();
        rInfo.setIdentity(rIdentity);
        r.setRouterInfo(rInfo);
        _pumper = new TunnelGatewayPumper(_context);
    }
    
    @Before
    public void setUp() {
        _config = prepareConfig(8);
        _preprocessor = new BatchedPreprocessor(_context,"test pre-processor");
        _sender = new InboundSender(_context, _config.getConfig(0));
        _receiver = new TestReceiver(_config);
        _gw = new PumpedTunnelGateway(_context, _preprocessor, _sender, _receiver, _pumper);
    }
    
    @Test
    public void testSmall() throws Exception {
    	int runCount = 1;
    	
        List messages = new ArrayList(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = new DataMessage(_context);
            m.setData(new byte[64]);
            java.util.Arrays.fill(m.getData(), (byte)0xFF);
            m.setMessageExpiration(_context.clock().now() + 60*1000);
            m.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            byte data[] = m.toByteArray();
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
            DataMessage m = new DataMessage(_context);
            m.setData(new byte[64]);
            java.util.Arrays.fill(m.getData(), (byte)0xFF);
            m.setMessageExpiration(_context.clock().now() + 60*1000);
            m.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            Hash to = new Hash(new byte[Hash.HASH_LENGTH]);
            java.util.Arrays.fill(to.getData(), (byte)0xFF);
            byte data[] = m.toByteArray();
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
            DataMessage m = new DataMessage(_context);
            m.setData(new byte[64]);
            java.util.Arrays.fill(m.getData(), (byte)0xFF);
            m.setMessageExpiration(_context.clock().now() + 60*1000);
            m.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            Hash to = new Hash(new byte[Hash.HASH_LENGTH]);
            java.util.Arrays.fill(to.getData(), (byte)0xFF);
            TunnelId tunnel = new TunnelId(42);
            byte data[] = m.toByteArray();
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
            DataMessage m = new DataMessage(_context);
            m.setData(new byte[1024]);
            java.util.Arrays.fill(m.getData(), (byte)0xFF);
            m.setMessageExpiration(_context.clock().now() + 60*1000);
            m.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            byte data[] = m.toByteArray();
            messages.add(m);
            _gw.add(m, null, null);
        }
        
        Thread.sleep(1000);
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    private static class TestRouterIdentity extends RouterIdentity {
        @Override
        public Hash getHash() {
            return Hash.FAKE_HASH;
        }
    }
    
    private class TestReceiver implements TunnelGateway.Receiver, FragmentHandler.DefragmentedReceiver {
        private TunnelCreatorConfig _config;
        private FragmentHandler _handler;
        private volatile List _received;
        public TestReceiver(TunnelCreatorConfig config) {
            _config = config;
            _handler = new FragmentHandler(_context, TestReceiver.this);
            _received = new ArrayList(1000);
        }
        public long receiveEncrypted(byte[] encrypted) {
            // fake all the hops...
            
            for (int i = 1; i <= _config.getLength() - 2; i++) {
                HopProcessor hop = new HopProcessor(_context, _config.getConfig(i));
                assertTrue(hop.process(encrypted, 0, encrypted.length, _config.getConfig(i).getReceiveFrom()));
            }
            
            // now handle it at the endpoint
            InboundEndpointProcessor end = new InboundEndpointProcessor(_context, _config);
            assertTrue(end.retrievePreprocessedData(encrypted, 0, encrypted.length, _config.getPeer(_config.getLength()-2)));
            
            
            _handler.receiveTunnelMessage(encrypted, 0, encrypted.length);
            return -1; // or do we need to return the real message ID?
        }        
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            System.out.println("got something");
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
    
    private TunnelCreatorConfig prepareConfig(int numHops) {
        Hash peers[] = new Hash[numHops];
        byte tunnelIds[][] = new byte[numHops][4];
        for (int i = 0; i < numHops; i++) {
            peers[i] = new Hash();
            peers[i].setData(new byte[Hash.HASH_LENGTH]);
            _context.random().nextBytes(peers[i].getData());
            _context.random().nextBytes(tunnelIds[i]);
        }
        
        TunnelCreatorConfig config = new TunnelCreatorConfig(_context, numHops, false);
        for (int i = 0; i < numHops; i++) {
            config.setPeer(i, peers[i]);
            HopConfig cfg = config.getConfig(i);
            cfg.setExpiration(_context.clock().now() + 60000);
            cfg.setIVKey(_context.keyGenerator().generateSessionKey());
            cfg.setLayerKey(_context.keyGenerator().generateSessionKey());
            if (i > 0)
                cfg.setReceiveFrom(peers[i-1]);
            else
                cfg.setReceiveFrom(null);
            cfg.setReceiveTunnelId(tunnelIds[i]);
            if (i < numHops - 1) {
                cfg.setSendTo(peers[i+1]);
                cfg.setSendTunnelId(tunnelIds[i+1]);
            } else {
                cfg.setSendTo(null);
                cfg.setSendTunnelId(null);
            }
        }
        return config;
    }
}
