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

import junit.framework.TestCase;
import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;

/**
 * Quick unit test for base functionality of outbound tunnel 
 * operation
 */
public class OutboundGatewayTest extends TestCase{
    private I2PAppContext _context;
    private TunnelCreatorConfig _config;
    private TunnelGateway.QueuePreprocessor _preprocessor;
    private TunnelGateway.Sender _sender;
    private TestReceiver _receiver;
    private TunnelGateway _gw;
    
    public void setUp() {
        _context = I2PAppContext.getGlobalContext();
        _config = prepareConfig(8);
        _preprocessor = new TrivialPreprocessor(_context);
        _sender = new OutboundSender(_context, _config);
        _receiver = new TestReceiver(_config);
        _gw = new TunnelGateway(_context, _preprocessor, _sender, _receiver);
    }
    
    public void testSmall() {
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
        
        long time = _context.clock().now() - start;
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    public void testRouter() {
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
        
        long time = _context.clock().now() - start;
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    public void testTunnel() {
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
        
        long time = _context.clock().now() - start;
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    public void testLarge() {
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
        
        long time = _context.clock().now() - start;
        //try { Thread.sleep(60*1000); } catch (Exception e) {}
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(received.contains(((I2NPMessage)messages.get(i))));
        }
    }
    
    private class TestReceiver implements TunnelGateway.Receiver, FragmentHandler.DefragmentedReceiver {
        private TunnelCreatorConfig _config;
        private FragmentHandler _handler;
        private List _received;
        public TestReceiver(TunnelCreatorConfig config) {
            _config = config;
            _handler = new FragmentHandler(_context, TestReceiver.this);
            _received = new ArrayList(1000);
        }
        public void receiveEncrypted(byte[] encrypted) {
            // fake all the hops...
            
            for (int i = 1; i < _config.getLength(); i++) {
                HopProcessor hop = new HopProcessor(_context, _config.getConfig(i));
                assertTrue(hop.process(encrypted, 0, encrypted.length, _config.getConfig(i).getReceiveFrom()));
                
            }
            

            _handler.receiveTunnelMessage(encrypted, 0, encrypted.length);
        }        
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            _received.add(msg);
        }
        public List clearReceived() { 
            List rv = _received; 
            _received = new ArrayList();
            return rv;
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
        
        TunnelCreatorConfig config = new TunnelCreatorConfig(numHops, false);
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
