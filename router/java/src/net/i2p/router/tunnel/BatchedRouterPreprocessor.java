package net.i2p.router.tunnel;

import java.util.List;
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
    private HopConfig _hopConfig;
    
    /** 
     * How frequently should we flush non-full messages, in milliseconds
     */
    public static final String PROP_BATCH_FREQUENCY = "batchFrequency";
    public static final String PROP_ROUTER_BATCH_FREQUENCY = "router.batchFrequency";
    public static final int DEFAULT_BATCH_FREQUENCY = 100;
    
    public BatchedRouterPreprocessor(RouterContext ctx) {
        this(ctx, (HopConfig)null);
    }
    public BatchedRouterPreprocessor(RouterContext ctx, TunnelCreatorConfig cfg) {
        super(ctx, getName(cfg));
        _routerContext = ctx;
        _config = cfg;
    }
    public BatchedRouterPreprocessor(RouterContext ctx, HopConfig cfg) {
        super(ctx, getName(cfg));
        _routerContext = ctx;
        _hopConfig = cfg;
    }
    
    private static String getName(HopConfig cfg) {
        if (cfg == null) return "[unknown]";
        if (cfg.getReceiveTunnel() != null)
            return cfg.getReceiveTunnel().getTunnelId() + "";
        else if (cfg.getSendTunnel() != null)
            return cfg.getSendTunnel().getTunnelId() + "";
        else
            return "[n/a]";
    }
    
    private static String getName(TunnelCreatorConfig cfg) {
        if (cfg == null) return "[unknown]";
        if (cfg.getReceiveTunnelId(0) != null)
            return cfg.getReceiveTunnelId(0).getTunnelId() + "";
        else if (cfg.getSendTunnelId(0) != null)
            return cfg.getSendTunnelId(0).getTunnelId() + "";
        else
            return "[n/a]";
    }

    /** how long should we wait before flushing */
    @Override
    protected long getSendDelay() { 
        String freq = null;
        if (_config != null) {
            Properties opts = _config.getOptions();
            if (opts != null)
                freq = opts.getProperty(PROP_BATCH_FREQUENCY);
        }
        if (freq == null)
            freq = _routerContext.getProperty(PROP_ROUTER_BATCH_FREQUENCY);
        
        if (freq != null) {
            try {
                return Integer.parseInt(freq);
            } catch (NumberFormatException nfe) {
                return DEFAULT_BATCH_FREQUENCY;
            }
        }
        return DEFAULT_BATCH_FREQUENCY;
    }
    
    @Override
    protected void notePreprocessing(long messageId, int numFragments, int totalLength, List messageIds, String msg) {
        if (_config != null)
            _routerContext.messageHistory().fragmentMessage(messageId, numFragments, totalLength, messageIds, _config, msg);
        else
            _routerContext.messageHistory().fragmentMessage(messageId, numFragments, totalLength, messageIds, _hopConfig, msg);
    }
}
