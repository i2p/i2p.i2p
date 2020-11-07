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

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;

/**
 * Quick unit test for base functionality of outbound tunnel 
 * operation
 *
 */
public class OutboundTest extends TestCase{
    private RouterContext _context;
    
    public void setUp() {
        _context = new RouterContext(null);
    }
    
    @SuppressWarnings("deprecation")
    public void testOutbound() {
        int numHops = 8;
        TunnelCreatorConfig config = prepareConfig(numHops);
        
        byte orig[] = new byte[1024];
        byte message[] = new byte[1024];
        _context.random().nextBytes(orig); // might as well fill the IV
        System.arraycopy(orig, 0, message, 0, message.length);
        
        OutboundGatewayProcessor p = new OutboundGatewayProcessor(_context, config);
        p.process(message, 0, message.length);
        
        for (int i = 1; i < numHops; i++) {
            HopProcessor hop = new HopProcessor(_context, config.getConfig(i));
            Hash prev = config.getConfig(i).getReceiveFrom();
            assertTrue(hop.process(message, 0, message.length, prev));
        }
        
        boolean eq = DataHelper.eq(orig, 16, message, 16, orig.length - 16);
        if (!eq) {
            System.out.println("Orig:\n" + net.i2p.util.HexDump.dump(orig, 16, orig.length - 16));
            System.out.println("Rcvd:\n" + net.i2p.util.HexDump.dump(message, 16, orig.length - 16));
        }
        assertTrue(eq);
    }
    
    private TunnelCreatorConfig prepareConfig(int numHops) {
        Hash peers[] = new Hash[numHops];
        long tunnelIds[] = new long[numHops];
        for (int i = 0; i < numHops; i++) {
            peers[i] = new Hash();
            peers[i].setData(new byte[Hash.HASH_LENGTH]);
            _context.random().nextBytes(peers[i].getData());
            tunnelIds[i] = 1 + _context.random().nextLong(TunnelId.MAX_ID_VALUE);
        }
        
        TunnelCreatorConfig config = new TCConfig(_context, numHops, false);
        for (int i = 0; i < numHops; i++) {
        	config.setPeer(i, peers[i]);
            HopConfig cfg = config.getConfig(i);
            cfg.setExpiration(_context.clock().now() + 60000);
            cfg.setIVKey(_context.keyGenerator().generateSessionKey());
            cfg.setLayerKey(_context.keyGenerator().generateSessionKey());
            if (i > 0)
                cfg.setReceiveFrom(peers[i-1]);
            cfg.setReceiveTunnelId(tunnelIds[i]);
            if (i < numHops - 1) {
                cfg.setSendTo(peers[i+1]);
                cfg.setSendTunnelId(tunnelIds[i+1]);
            }
        }
        return config;
    }
}
