package net.i2p.router.tunnel;

import java.util.Properties;
import net.i2p.router.RouterContext;

/** 
 * Honor the 'batchFrequency' tunnel pool setting or the 'router.batchFrequency'
 * router config setting, and track fragmentation.
 *
 */
public class BatchedRouterPreprocessor extends BatchedPreprocessor {
    private RouterContext _routerContext;
    private TunnelCreatorConfig _config;
    
    /** 
     * How frequently should we flush non-full messages, in milliseconds
     */
    public static final String PROP_BATCH_FREQUENCY = "batchFrequency";
    public static final String PROP_ROUTER_BATCH_FREQUENCY = "router.batchFrequency";
    public static final int DEFAULT_BATCH_FREQUENCY = 0;
    
    public BatchedRouterPreprocessor(RouterContext ctx) {
        this(ctx, null);
    }
    public BatchedRouterPreprocessor(RouterContext ctx, TunnelCreatorConfig cfg) {
        super(ctx);
        _routerContext = ctx;
        _config = cfg;
    }

    /** how long should we wait before flushing */
    protected long getSendDelay() { 
        String freq = null;
        if (_config != null) {
            Properties opts = _config.getOptions();
            if (opts != null)
                freq = opts.getProperty(PROP_BATCH_FREQUENCY);
        } else {
            freq = _routerContext.getProperty(PROP_ROUTER_BATCH_FREQUENCY);
        }
        
        if (freq != null) {
            try {
                return Integer.parseInt(freq);
            } catch (NumberFormatException nfe) {
                return DEFAULT_BATCH_FREQUENCY;
            }
        }
        return DEFAULT_BATCH_FREQUENCY;
    }
    
    protected void notePreprocessing(long messageId, int numFragments) {
        _routerContext.messageHistory().fragmentMessage(messageId, numFragments);
    }
}
