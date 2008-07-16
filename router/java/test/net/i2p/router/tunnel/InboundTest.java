package net.i2p.router.tunnel;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import junit.framework.TestCase;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

/**
 * Quick unit test for base functionality of inbound tunnel 
 * operation
 *
 */
public class InboundTest extends TestCase{
    private I2PAppContext _context;
    
    public void setUp() {
        _context = I2PAppContext.getGlobalContext();
    }
    
    public void testInbound() {
    	int numHops = 8;
    	TunnelCreatorConfig config = prepareConfig(numHops);
    	
        byte orig[] = new byte[128];
        byte message[] = new byte[128];
        _context.random().nextBytes(orig); // might as well fill the IV
        System.arraycopy(orig, 0, message, 0, message.length);
        
        InboundGatewayProcessor p = new InboundGatewayProcessor(_context, config.getConfig(0));
        p.process(message, 0, message.length, null);
        
        for (int i = 1; i < numHops-1; i++) {
            HopProcessor hop = new HopProcessor(_context, config.getConfig(i));
            Hash prev = config.getConfig(i).getReceiveFrom();
            assertTrue(hop.process(message, 0, message.length, prev));
        }
        
        InboundEndpointProcessor end = new InboundEndpointProcessor(_context, config);
        assertTrue(end.retrievePreprocessedData(message, 0, message.length, config.getPeer(numHops-2)));
        
        assertTrue(DataHelper.eq(orig, 16, message, 16, orig.length - 16));
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
