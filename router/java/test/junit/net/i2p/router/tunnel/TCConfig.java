package net.i2p.router.tunnel;

import net.i2p.router.RouterContext;

/**
 * Since TunnelCreatorConfig is now abstract
 * @since 0.9.42
 */
public class TCConfig extends TunnelCreatorConfig {
    
    public TCConfig(RouterContext ctx, int length, boolean isInbound) {
        super(ctx, length, isInbound);
    }
}
