package net.i2p.router.tunnel;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */


import static junit.framework.TestCase.*;

/**
 * Quick unit test for base functionality of inbound tunnel 
 * operation
 */
public class InboundGatewayIT extends GatewayITBase {
    
    
    @Override
    protected void setupSenderAndReceiver() {
        _sender = new InboundSender(_context, _config.getConfig(0));
        _receiver = new InboundTestReceiver(_config);
    }
    
    @Override
    protected int getLastHop() {
        return 2;
    }
    
    private class InboundTestReceiver extends TestReceiver {
        public InboundTestReceiver(TunnelCreatorConfig config) {
            super(config);
        }
        
        @Override
        @SuppressWarnings("deprecation")
        protected void handleAtEndpoint(byte []encrypted) {
            // now handle it at the endpoint
            InboundEndpointProcessor end = new InboundEndpointProcessor(_context, _config);
            assertTrue(end.retrievePreprocessedData(encrypted, 0, encrypted.length, _config.getPeer(_config.getLength()-2)));
        }
    }
}
