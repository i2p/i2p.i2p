package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Quick unit test for base functionality of inbound tunnel 
 * operation
 */
public class InboundTest {
    private I2PAppContext _context;
    private Log _log;
    
    public InboundTest() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(InboundTest.class);
    }
    
    public void runTest() {
        int numHops = 8;
        TunnelCreatorConfig config = prepareConfig(numHops);
        long start = _context.clock().now();
        for (int i = 0; i < 1; i++) 
            runTest(numHops, config);
        long time = _context.clock().now() - start;
        _log.debug("Time for 1000 messages: " + time);
    }
    
    private void runTest(int numHops, TunnelCreatorConfig config) {
        byte orig[] = new byte[128];
        byte message[] = new byte[128];
        _context.random().nextBytes(orig); // might as well fill the IV
        System.arraycopy(orig, 0, message, 0, message.length);
        
        _log.debug("orig: \n" + Base64.encode(orig, 16, orig.length-16));
        InboundGatewayProcessor p = new InboundGatewayProcessor(_context, config.getConfig(0));
        p.process(message, 0, message.length, null);
        
        for (int i = 1; i < numHops-1; i++) {
            HopProcessor hop = new HopProcessor(_context, config.getConfig(i));
            Hash prev = config.getConfig(i).getReceiveFrom();
            boolean ok = hop.process(message, 0, message.length, prev);
            if (!ok)
                _log.error("Error processing at hop " + i);
            //else
            //    _log.info("Processing OK at hop " + i);
        }
        
        InboundEndpointProcessor end = new InboundEndpointProcessor(_context, config);
        boolean ok = end.retrievePreprocessedData(message, 0, message.length, config.getPeer(numHops-2));
        if (!ok) {
            _log.error("Error retrieving cleartext at the endpoint");
            try { Thread.sleep(5*1000); } catch (Exception e) {}
        }
        
        //_log.debug("After: " + Base64.encode(message, 16, orig.length-16));
        boolean eq = DataHelper.eq(orig, 16, message, 16, orig.length - 16);
        _log.info("equal? " + eq);
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
        InboundTest test = new InboundTest();
        test.runTest();
    }
}
