package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;

/**
 * Quick unit test for base functionality of outbound tunnel 
 * operation
 *
 */
public class OutboundTest {
    private I2PAppContext _context;
    private Log _log;
    
    public OutboundTest() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(OutboundTest.class);
    }
    
    public void runTest() {
        int numHops = 8;
        TunnelCreatorConfig config = prepareConfig(numHops);
        
        byte orig[] = new byte[1024];
        byte message[] = new byte[1024];
        _context.random().nextBytes(orig); // might as well fill the IV
        System.arraycopy(orig, 0, message, 0, message.length);
        
        OutboundGatewayProcessor p = new OutboundGatewayProcessor(_context, config);
        p.process(message, 0, message.length);
        
        for (int i = 0; i < numHops; i++) {
            HopProcessor hop = new HopProcessor(_context, config.getConfig(i));
            Hash prev = config.getConfig(i).getReceiveFrom();
            boolean ok = hop.process(message, 0, message.length, prev);
            if (!ok)
                _log.error("Error processing at hop " + i);
            //else
            //    _log.info("Processing OK at hop " + i);
        }
        
        _log.debug("After: " + Base64.encode(message, 16, orig.length-16));
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
        OutboundTest test = new OutboundTest();
        test.runTest();
    }
}
