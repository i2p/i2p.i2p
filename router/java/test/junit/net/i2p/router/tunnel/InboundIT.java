package net.i2p.router.tunnel;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import org.junit.Test;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

import static org.junit.Assert.assertTrue;

/**
 * Quick unit test for base functionality of inbound tunnel 
 * operation
 *
 */
public class InboundIT extends RouterITBase {
    
    @Test
    @SuppressWarnings("deprecation")
    public void testInbound() {
    	int numHops = 8;
    	
        byte orig[] = new byte[128];
        byte message[] = new byte[128];
        _context.random().nextBytes(orig); // might as well fill the IV
        System.arraycopy(orig, 0, message, 0, message.length);
        
        InboundGatewayProcessor p = new InboundGatewayProcessor(_context, _config.getConfig(0));
        p.process(message, 0, message.length, null);
        
        for (int i = 1; i < numHops-1; i++) {
            HopProcessor hop = new HopProcessor(_context, _config.getConfig(i));
            Hash prev = _config.getConfig(i).getReceiveFrom();
            assertTrue(hop.process(message, 0, message.length, prev));
        }
        
        InboundEndpointProcessor end = new InboundEndpointProcessor(_context, _config);
        assertTrue(end.retrievePreprocessedData(message, 0, message.length, _config.getPeer(numHops-2)));
        
        assertTrue(DataHelper.eq(orig, 16, message, 16, orig.length - 16));
    }
    
}
