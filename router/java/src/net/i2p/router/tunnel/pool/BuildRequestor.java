package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.data.i2np.VariableTunnelBuildMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.tunnel.BuildMessageGenerator;
import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

/**
 *
 */
class BuildRequestor {
    private static final List<Integer> ORDER = new ArrayList(BuildMessageGenerator.ORDER.length);
    static {
        for (int i = 0; i < BuildMessageGenerator.ORDER.length; i++)
            ORDER.add(Integer.valueOf(i));
    }
    private static final int PRIORITY = 500;
    /**
     *  At 10 seconds, we were receiving about 20% of replies after expiration
     *  Todo: make this variable on a per-request basis, to account for tunnel length,
     *  expl. vs. client, uptime, and network conditions.
     *  Put the expiration in the PTCC.
     *
     *  Also, perhaps, save the PTCC even after expiration for an extended time,
     *  so can we use a successfully built tunnel anyway.
     *
     */
    static final int REQUEST_TIMEOUT = 13*1000;

    /** make this shorter than REQUEST_TIMEOUT */
    private static final int FIRST_HOP_TIMEOUT = 10*1000;
    
    /** some randomization is added on to this */
    private static final int BUILD_MSG_TIMEOUT = 60*1000;

    private static boolean usePairedTunnels(RouterContext ctx) {
        String val = ctx.getProperty("router.usePairedTunnels");
        if ( (val == null) || (Boolean.valueOf(val).booleanValue()) )
            return true;
        else
            return false;
    }
    
    /** new style requests need to fill in the tunnel IDs before hand */
    private static void prepare(RouterContext ctx, PooledTunnelCreatorConfig cfg) {
        for (int i = 0; i < cfg.getLength(); i++) {
            if ( (!cfg.isInbound()) && (i == 0) ) {
                // outbound gateway (us) doesn't receive on a tunnel id
                if (cfg.getLength() <= 1) // zero hop, pretend to have a send id
                    cfg.getConfig(i).setSendTunnelId(DataHelper.toLong(4, ctx.random().nextLong(TunnelId.MAX_ID_VALUE)));
            } else {
                cfg.getConfig(i).setReceiveTunnelId(DataHelper.toLong(4, ctx.random().nextLong(TunnelId.MAX_ID_VALUE)));
            }
            
            if (i > 0)
                cfg.getConfig(i-1).setSendTunnelId(cfg.getConfig(i).getReceiveTunnelId());
            byte iv[] = new byte[16];
            ctx.random().nextBytes(iv);
            cfg.getConfig(i).setReplyIV(new ByteArray(iv));
            cfg.getConfig(i).setReplyKey(ctx.keyGenerator().generateSessionKey());
        }
        // This is in BuildExecutor.buildTunnel() now
        // And it was overwritten by the one in createTunnelBuildMessage() anyway!
        //cfg.setReplyMessageId(ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE));
    }

    /**
     *  @param cfg ReplyMessageId must be set
     */
    public static void request(RouterContext ctx, TunnelPool pool, PooledTunnelCreatorConfig cfg, BuildExecutor exec) {
        // new style crypto fills in all the blanks, while the old style waits for replies to fill in the next hop, etc
        prepare(ctx, cfg);
        
        if (cfg.getLength() <= 1) {
            buildZeroHop(ctx, pool, cfg, exec);
            return;
        }
        
        Log log = ctx.logManager().getLog(BuildRequestor.class);
        cfg.setTunnelPool(pool);
        
        TunnelInfo pairedTunnel = null;
        if (pool.getSettings().isExploratory() || !usePairedTunnels(ctx)) {
            if (pool.getSettings().isInbound())
                pairedTunnel = ctx.tunnelManager().selectOutboundTunnel();
            else
                pairedTunnel = ctx.tunnelManager().selectInboundTunnel();
        } else {
            if (pool.getSettings().isInbound())
                pairedTunnel = ctx.tunnelManager().selectOutboundTunnel(pool.getSettings().getDestination());
            else
                pairedTunnel = ctx.tunnelManager().selectInboundTunnel(pool.getSettings().getDestination());
        }
        if (pairedTunnel == null) {   
            if (log.shouldLog(Log.WARN))
                log.warn("Couldn't find a paired tunnel for " + cfg + ", fall back on exploratory tunnels for pairing");
            if (!pool.getSettings().isExploratory() && usePairedTunnels(ctx))
                if (pool.getSettings().isInbound())
                    pairedTunnel = ctx.tunnelManager().selectOutboundTunnel();
                else
                    pairedTunnel = ctx.tunnelManager().selectInboundTunnel();
        }
        if (pairedTunnel == null) {
            if (log.shouldLog(Log.ERROR))
                log.error("Tunnel build failed, as we couldn't find a paired tunnel for " + cfg);
            exec.buildComplete(cfg, pool);
            return;
        }
        
        long beforeCreate = System.currentTimeMillis();
        TunnelBuildMessage msg = createTunnelBuildMessage(ctx, pool, cfg, pairedTunnel, exec);
        long createTime = System.currentTimeMillis()-beforeCreate;
        if (msg == null) {
            if (log.shouldLog(Log.WARN))
                log.warn("Tunnel build failed, as we couldn't create the tunnel build message for " + cfg);
            exec.buildComplete(cfg, pool);
            return;
        }
        
        //cfg.setPairedTunnel(pairedTunnel);
        
        long beforeDispatch = System.currentTimeMillis();
        if (cfg.isInbound()) {
            if (log.shouldLog(Log.INFO))
                log.info("Sending the tunnel build request " + msg.getUniqueId() + " out the tunnel " + pairedTunnel + " to " 
                          + cfg.getPeer(0).toBase64() + " for " + cfg + " waiting for the reply of "
                          + cfg.getReplyMessageId());
            // send it out a tunnel targetting the first hop
            ctx.tunnelDispatcher().dispatchOutbound(msg, pairedTunnel.getSendTunnelId(0), cfg.getPeer(0));
        } else {
            if (log.shouldLog(Log.INFO))
                log.info("Sending the tunnel build request directly to " + cfg.getPeer(1).toBase64() 
                          + " for " + cfg + " waiting for the reply of " + cfg.getReplyMessageId() 
                          + " with msgId=" + msg.getUniqueId());
            // send it directly to the first hop
            OutNetMessage outMsg = new OutNetMessage(ctx);
            // Add some fuzz to the TBM expiration to make it harder to guess how many hops
            // or placement in the tunnel
            msg.setMessageExpiration(ctx.clock().now() + BUILD_MSG_TIMEOUT + ctx.random().nextLong(20*1000));
            // We set the OutNetMessage expiration much shorter, so that the
            // TunnelBuildFirstHopFailJob fires before the 13s build expiration.
            outMsg.setExpiration(ctx.clock().now() + FIRST_HOP_TIMEOUT);
            outMsg.setMessage(msg);
            outMsg.setPriority(PRIORITY);
            RouterInfo peer = ctx.netDb().lookupRouterInfoLocally(cfg.getPeer(1));
            if (peer == null) {
                if (log.shouldLog(Log.ERROR))
                    log.error("Could not find the next hop to send the outbound request to: " + cfg);
                exec.buildComplete(cfg, pool);
                return;
            }
            outMsg.setTarget(peer);
            outMsg.setOnFailedSendJob(new TunnelBuildFirstHopFailJob(ctx, pool, cfg, exec));
            ctx.outNetMessagePool().add(outMsg);
        }
        if (log.shouldLog(Log.DEBUG))
            log.debug("Tunnel build message " + msg.getUniqueId() + " created in " + createTime
                      + "ms and dispatched in " + (System.currentTimeMillis()-beforeDispatch));
    }
    
    private static final String MIN_VARIABLE_VERSION = "0.7.12";
    /** change this to true in 0.7.13 if testing goes well */
    private static final boolean SEND_VARIABLE = true;
    /** 5 (~2600 bytes) fits nicely in 3 tunnel messages */
    private static final int SHORT_RECORDS = 5;
    private static final int LONG_RECORDS = TunnelBuildMessage.MAX_RECORD_COUNT;
    private static final VersionComparator _versionComparator = new VersionComparator();
    private static final List<Integer> SHORT_ORDER = new ArrayList(SHORT_RECORDS);
    static {
        for (int i = 0; i < SHORT_RECORDS; i++)
            SHORT_ORDER.add(Integer.valueOf(i));
    }

    /** @since 0.7.12 */
    private static boolean supportsVariable(RouterContext ctx, Hash h) {
        RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(h);
        if (ri == null)
            return false;
        String v = ri.getOption("router.version");
        if (v == null)
            return false;
        return _versionComparator.compare(v, MIN_VARIABLE_VERSION) >= 0;
    }

    /**
     *  If the tunnel is short enough, and everybody in the tunnel, and the
     *  OBEP or IBGW for the paired tunnel, all support the new variable-sized tunnel build message,
     *  then use that, otherwise the old 8-entry version.
     *  @return null on error
     */
    private static TunnelBuildMessage createTunnelBuildMessage(RouterContext ctx, TunnelPool pool, PooledTunnelCreatorConfig cfg, TunnelInfo pairedTunnel, BuildExecutor exec) {
        Log log = ctx.logManager().getLog(BuildRequestor.class);
        long replyTunnel = 0;
        Hash replyRouter = null;
        boolean useVariable = SEND_VARIABLE && cfg.getLength() <= SHORT_RECORDS;
        if (cfg.isInbound()) {
            //replyTunnel = 0; // as above
            replyRouter = ctx.routerHash();
            if (useVariable) {
                // check the reply OBEP and all the tunnel peers except ourselves
                if (!supportsVariable(ctx, pairedTunnel.getPeer(pairedTunnel.getLength() - 1))) {
                    useVariable = false;
                } else {
                    for (int i = 0; i < cfg.getLength() - 1; i++) {
                        if (!supportsVariable(ctx, cfg.getPeer(i))) {
                            useVariable = false;
                            break;
                        }
                    }
                }
            }
        } else {
            replyTunnel = pairedTunnel.getReceiveTunnelId(0).getTunnelId();
            replyRouter = pairedTunnel.getPeer(0);
            if (useVariable) {
                // check the reply IBGW and all the tunnel peers except ourselves
                if (!supportsVariable(ctx, replyRouter)) {
                    useVariable = false;
                } else {
                    for (int i = 1; i < cfg.getLength() - 1; i++) {
                        if (!supportsVariable(ctx, cfg.getPeer(i))) {
                            useVariable = false;
                            break;
                        }
                    }
                }
            }
        }

        // populate and encrypt the message
        TunnelBuildMessage msg;
        List<Integer> order;
        if (useVariable) {
            msg = new VariableTunnelBuildMessage(ctx, SHORT_RECORDS);
            order = new ArrayList(SHORT_ORDER);
            if (log.shouldLog(Log.INFO))
                log.info("Using new VTBM");
        } else {
            msg = new TunnelBuildMessage(ctx);
            order = new ArrayList(ORDER);
        }

        // This is in BuildExecutor.buildTunnel() now
        //long replyMessageId = ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE);
        //cfg.setReplyMessageId(replyMessageId);
        
        Collections.shuffle(order, ctx.random()); // randomized placement within the message
        cfg.setReplyOrder(order);
        
        if (log.shouldLog(Log.DEBUG))
            log.debug("Build order: " + order + " for " + cfg);
        
        for (int i = 0; i < msg.getRecordCount(); i++) {
            int hop = ((Integer)order.get(i)).intValue();
            PublicKey key = null;
    
            if (BuildMessageGenerator.isBlank(cfg, hop)) {
                // erm, blank
            } else {
                Hash peer = cfg.getPeer(hop);
                RouterInfo peerInfo = ctx.netDb().lookupRouterInfoLocally(peer);
                if (peerInfo == null) {
                    if (log.shouldLog(Log.WARN))
                        log.warn("Peer selected for hop " + i + "/" + hop + " was not found locally: " 
                                  + peer.toBase64() + " for " + cfg);
                    return null;
                } else {
                    key = peerInfo.getIdentity().getPublicKey();
                }
            }
            if (log.shouldLog(Log.DEBUG))
                log.debug(cfg.getReplyMessageId() + ": record " + i + "/" + hop + " has key " + key);
            BuildMessageGenerator.createRecord(i, hop, msg, cfg, replyRouter, replyTunnel, ctx, key);
        }
        BuildMessageGenerator.layeredEncrypt(ctx, msg, cfg, order);
        
        return msg;
    }
    
    private static void buildZeroHop(RouterContext ctx, TunnelPool pool, PooledTunnelCreatorConfig cfg, BuildExecutor exec) {
        Log log = ctx.logManager().getLog(BuildRequestor.class);
        if (log.shouldLog(Log.DEBUG))
            log.debug("Build zero hop tunnel " + cfg);            

        exec.buildComplete(cfg, pool);
        if (cfg.isInbound())
            ctx.tunnelDispatcher().joinInbound(cfg);
        else
            ctx.tunnelDispatcher().joinOutbound(cfg);
        pool.addTunnel(cfg);
        exec.buildSuccessful(cfg);
        ExpireJob expireJob = new ExpireJob(ctx, cfg, pool);
        cfg.setExpireJob(expireJob);
        ctx.jobQueue().addJob(expireJob);
        // can it get much easier?
    }

    /**
     *  Do two important things if we can't get the build msg to the
     *  first hop on an outbound tunnel -
     *  - Call buildComplete() so we can get started on the next build
     *    without waiting for the full expire time
     *  - Blame the first hop in the profile
     *  Most likely to happen on an exploratory tunnel, obviously.
     *  Can't do this for inbound tunnels since the msg goes out an expl. tunnel.
     */
    private static class TunnelBuildFirstHopFailJob extends JobImpl {
        TunnelPool _pool;
        PooledTunnelCreatorConfig _cfg;
        BuildExecutor _exec;
        private TunnelBuildFirstHopFailJob(RouterContext ctx, TunnelPool pool, PooledTunnelCreatorConfig cfg, BuildExecutor exec) {
            super(ctx);
            _cfg = cfg;
            _exec = exec;
            _pool = pool;
        }
        public String getName() { return "Timeout contacting first peer for OB tunnel"; }
        public void runJob() {
            _exec.buildComplete(_cfg, _pool);
            getContext().profileManager().tunnelTimedOut(_cfg.getPeer(1));
            getContext().statManager().addRateData("tunnel.buildFailFirstHop", 1, 0);
            // static, no _log
            //System.err.println("Cant contact first hop for " + _cfg);
        }
    }
}
