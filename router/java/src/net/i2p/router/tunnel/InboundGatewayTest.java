package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.util.Log;

/**
 * Quick unit test for base functionality of inbound tunnel 
 * operation
 */
public class InboundGatewayTest {
    private I2PAppContext _context;
    private Log _log;
    private TunnelCreatorConfig _config;
    private TunnelGateway.QueuePreprocessor _preprocessor;
    private TunnelGateway.Sender _sender;
    private TestReceiver _receiver;
    private TunnelGateway _gw;
    
    public InboundGatewayTest() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(InboundGatewayTest.class);
    }
    
    public void runTest() {
        int numHops = 8;
        int runCount = 1;
        _config = prepareConfig(numHops);
        _preprocessor = new TrivialPreprocessor(_context);
        _sender = new InboundSender(_context, _config.getConfig(0));
        _receiver = new TestReceiver(_config);
        _gw = new TunnelGateway(_context, _preprocessor, _sender, _receiver);
        
        // single fragment
        testSmall(runCount);
        // includes target router instructions
        testRouter(runCount);
        // includes target router & tunnel instructions
        testTunnel(runCount);
        // multiple fragments
        testLarge(runCount);
     
        try { Thread.sleep(5*1000); } catch (Exception e) {}
    }
    
    private void testSmall(int runCount) {
        List messages = new ArrayList(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = new DataMessage(_context);
            m.setData(new byte[64]);
            java.util.Arrays.fill(m.getData(), (byte)0xFF);
            m.setMessageExpiration(new Date(_context.clock().now() + 60*1000));
            m.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            _log.debug("Sending " + m.getUniqueId());
            byte data[] = m.toByteArray();
            _log.debug("SEND(" + data.length + "): " + Base64.encode(data) + " " + _context.sha().calculateHash(data).toBase64());
            messages.add(m);
            _gw.add(m, null, null);
        }
        
        long time = _context.clock().now() - start;
        _log.debug("Time for " + runCount + " messages: " + time);   
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            if (!received.contains(((I2NPMessage)messages.get(i)))) {
                _log.error("Message " + i + " not received");
            }
        }
    }
    
    private void testRouter(int runCount) {
        List messages = new ArrayList(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = new DataMessage(_context);
            m.setData(new byte[64]);
            java.util.Arrays.fill(m.getData(), (byte)0xFF);
            m.setMessageExpiration(new Date(_context.clock().now() + 60*1000));
            m.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            Hash to = new Hash(new byte[Hash.HASH_LENGTH]);
            java.util.Arrays.fill(to.getData(), (byte)0xFF);
            _log.debug("Sending " + m.getUniqueId() + " to " + to);
            byte data[] = m.toByteArray();
            _log.debug("SEND(" + data.length + "): " + Base64.encode(data) + " " + _context.sha().calculateHash(data).toBase64());
            messages.add(m);
            _gw.add(m, to, null);
        }
        
        long time = _context.clock().now() - start;
        _log.debug("Time for " + runCount + " messages: " + time);   
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            if (!received.contains(((I2NPMessage)messages.get(i)))) {
                _log.error("Message " + i + " not received");
            }
        }
    }
    
    private void testTunnel(int runCount) {
        List messages = new ArrayList(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = new DataMessage(_context);
            m.setData(new byte[64]);
            java.util.Arrays.fill(m.getData(), (byte)0xFF);
            m.setMessageExpiration(new Date(_context.clock().now() + 60*1000));
            m.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            Hash to = new Hash(new byte[Hash.HASH_LENGTH]);
            java.util.Arrays.fill(to.getData(), (byte)0xFF);
            TunnelId tunnel = new TunnelId(42);
            _log.debug("Sending " + m.getUniqueId() + " to " + to + "/" + tunnel);
            byte data[] = m.toByteArray();
            _log.debug("SEND(" + data.length + "): " + Base64.encode(data) + " " + _context.sha().calculateHash(data).toBase64());
            messages.add(m);
            _gw.add(m, to, tunnel);
        }
        
        long time = _context.clock().now() - start;
        _log.debug("Time for " + runCount + " messages: " + time);   
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            if (!received.contains(((I2NPMessage)messages.get(i)))) {
                _log.error("Message " + i + " not received");
            }
        }
    }
    
    private void testLarge(int runCount) {
        List messages = new ArrayList(runCount);
        long start = _context.clock().now();
    
        for (int i = 0; i < runCount; i++) {
            DataMessage m = new DataMessage(_context);
            m.setData(new byte[1024]);
            java.util.Arrays.fill(m.getData(), (byte)0xFF);
            m.setMessageExpiration(new Date(_context.clock().now() + 60*1000));
            m.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            _log.debug("Sending " + m.getUniqueId());
            byte data[] = m.toByteArray();
            _log.debug("SEND(" + data.length + "): " + Base64.encode(data) + " " + _context.sha().calculateHash(data).toBase64());
            messages.add(m);
            _gw.add(m, null, null);
        }
        
        long time = _context.clock().now() - start;
        try { Thread.sleep(60*1000); } catch (Exception e) {}
        _log.debug("Time for " + runCount + " messages: " + time);   
        
        List received = _receiver.clearReceived();
        for (int i = 0; i < messages.size(); i++) {
            if (!received.contains(((I2NPMessage)messages.get(i)))) {
                _log.error("Message " + i + " not received");
            }
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
            
            for (int i = 1; i <= _config.getLength() - 1; i++) {
                HopProcessor hop = new HopProcessor(_context, _config.getConfig(i));
                boolean ok = hop.process(encrypted, 0, encrypted.length, _config.getConfig(i).getReceiveFrom());
                if (!ok)
                    _log.error("Error processing at hop " + i);
                //else
                //    _log.info("Processing OK at hop " + i);
            }
            
            // now handle it at the endpoint
            InboundEndpointProcessor end = new InboundEndpointProcessor(_context, _config);
            boolean ok = end.retrievePreprocessedData(encrypted, 0, encrypted.length, _config.getPeer(_config.getLength()-1));
            if (!ok)
                _log.error("Error retrieving cleartext at the endpoint");
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Received      " + Base64.encode(encrypted));

            _handler.receiveTunnelMessage(encrypted, 0, encrypted.length);
            _log.debug("\n\ndone receiving message\n\n");
        }        
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Completed     " + msg.getUniqueId() + " to " + toRouter + "/" + toTunnel);
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
    
    public static void main(String args[]) {
        InboundGatewayTest test = new InboundGatewayTest();
        test.runTest();
    }
}
