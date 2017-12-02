package net.i2p.router.tunnel;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Quick unit test for base functionality of outbound tunnel 
 * operation
 */
public class OutboundGatewayIT extends GatewayITBase {
    
    @Override
    protected void setupSenderAndReceiver() {
        _sender = new OutboundSender(_context, _config);
        _receiver = new TestReceiver(_config);
    }
    
    @Override
    protected int getLastHop() {
        return 1;
    }
}
