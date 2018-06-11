package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.data.i2np.VariableTunnelBuildMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.tunnel.BuildMessageGenerator;
import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

/**
 *  Methods for creating Tunnel Build Messages, i.e. requests
 */
abstract class BuildRequestor {
    private static final List<Integer> ORDER = new ArrayList<Integer>(TunnelBuildMessage.MAX_RECORD_COUNT);
    //private static final String MIN_VARIABLE_VERSION = "0.7.12";
    private static final boolean SEND_VARIABLE = true;
    private static final int SHORT_RECORDS = 4;
    private static final List<Integer> SHORT_ORDER = new ArrayList<Integer>(SHORT_RECORDS);
    /** 5 (~2600 bytes) fits nicely in 3 tunnel messages */
    private static final int MEDIUM_RECORDS = 5;
    private static final List<Integer> MEDIUM_ORDER = new ArrayList<Integer>(MEDIUM_RECORDS);
    static {
        for (int i = 0; i < TunnelBuildMessage.MAX_RECORD_COUNT; i++) {
            ORDER.add(Integer.valueOf(i));
        }
        for (int i = 0; i < SHORT_RECORDS; i++) {
            SHORT_ORDER.add(Integer.valueOf(i));
        }
        for (int i = 0; i < MEDIUM_RECORDS; i++) {
            MEDIUM_ORDER.add(Integer.valueOf(i));
        }
    }

    private static final int PRIORITY = OutNetMessage.PRIORITY_MY_BUILD_REQUEST;

    /**
     *  At 10 seconds, we were receiving about 20% of replies after expiration
     *  Todo: make this variable on a per-request basis, to account for tunnel length,
     *  expl. vs. client, uptime, and network conditions.
     *  Put the expiration in the PTCC.
     *
     *  Also, we now save the PTCC even after expiration for an extended time,
     *  so can we use a successfully built tunnel anyway.
     *
     */
    static final int REQUEST_TIMEOUT = 13*1000;

    /** make this shorter than REQUEST_TIMEOUT */
    private static final int FIRST_HOP_TIMEOUT = 10*1000;
    
    /** some randomization is added on to this */
    private static final int BUILD_MSG_TIMEOUT = 60*1000;

    /**
     *  "paired tunnels" means using a client's own inbound tunnel to receive the
     *  reply for an outbound build request, and using a client's own outbound tunnel
     *  to send an inbound build request.
     *  This is more secure than using the router's exploratory tunnels, as it
     *  makes correlation of multiple clients more difficult.
     *  @return true always
     */
    private static boolean usePairedTunnels(RouterContext ctx) {
        return true;
        //return ctx.getBooleanPropertyDefaultTrue("router.usePairedTunnels");
    }
    
    /** new style requests need to fill in the tunnel IDs before hand */
    private static void prepare(RouterContext ctx, PooledTunnelCreatorConfig cfg) {
        int len = cfg.getLength();
        boolean isIB = cfg.isInbound();
        for (int i = 0; i < len; i++) {
            if ( (!isIB) && (i == 0) ) {
                // outbound gateway (us) doesn't receive on a tunnel id
                if (len <= 1)  { // zero hop, pretend to have a send id
                    long id = ctx.tunnelDispatcher().getNewOBGWID();
                    cfg.getConfig(i).setSendTunnelId(DataHelper.toLong(4, id));
                }
            } else {
                long id;
                if (isIB && len == 1)
                    id = ctx.tunnelDispatcher().getNewIBZeroHopID();
                else if (isIB && i == len - 1)
                    id = ctx.tunnelDispatcher().getNewIBEPID();
                else
                    id = 1 + ctx.random().nextLong(TunnelId.MAX_ID_VALUE);
                cfg.getConfig(i).setReceiveTunnelId(DataHelper.toLong(4, id));
            }
            
            if (i > 0)
                cfg.getConfig(i-1).setSendTunnelId(cfg.getConfig(i).getReceiveTunnelId());
            byte iv[] = new byte[16];
            ctx.random().nextBytes(iv);
            cfg.getConfig(i).setReplyIV(iv);
            cfg.getConfig(i).setReplyKey(ctx.keyGenerator().generateSessionKey());
        }
        // This is in BuildExecutor.buildTunnel() now
        // And it was overwritten by the one in createTunnelBuildMessage() anyway!
        //cfg.setReplyMessageId(ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE));
    }

    /**
     *  Send out a build request message.
     *
     *  @param cfg ReplyMessageId must be set
     *  @return success
     */
    public static boolean request(RouterContext ctx, TunnelPool pool,
                                  PooledTunnelCreatorConfig cfg, BuildExecutor exec) {
        // new style crypto fills in all the blanks, while the old style waits for replies to fill in the next hop, etc
        prepare(ctx, cfg);
        
        if (cfg.getLength() <= 1) {
            buildZeroHop(ctx, pool, cfg, exec);
            return true;
        }
        
        Log log = ctx.logManager().getLog(BuildRequestor.class);
        cfg.setTunnelPool(pool);
        
        TunnelInfo pairedTunnel = null;
        Hash farEnd = cfg.getFarEnd();
        TunnelManagerFacade mgr = ctx.tunnelManager();
        boolean isInbound = pool.getSettings().isInbound();
        if (pool.getSettings().isExploratory() || !usePairedTunnels(ctx)) {
            if (isInbound)
                pairedTunnel = mgr.selectOutboundExploratoryTunnel(farEnd);
            else
                pairedTunnel = mgr.selectInboundExploratoryTunnel(farEnd);
        } else {
            // building a client tunnel
            if (isInbound)
                pairedTunnel = mgr.selectOutboundTunnel(pool.getSettings().getDestination(), farEnd);
            else
                pairedTunnel = mgr.selectInboundTunnel(pool.getSettings().getDestination(), farEnd);
            if (pairedTunnel == null) {   
                if (isInbound) {
                    // random more reliable than closest ??
                    //pairedTunnel = mgr.selectOutboundExploratoryTunnel(farEnd);
                    pairedTunnel = mgr.selectOutboundTunnel();
                    if (pairedTunnel != null &&
                        pairedTunnel.getLength() <= 1 &&
                        mgr.getOutboundSettings().getLength() > 0 &&
                        mgr.getOutboundSettings().getLength() + mgr.getOutboundSettings().getLengthVariance() > 0) {
                        // don't build using a zero-hop expl.,
                        // as it is both very bad for anonomyity,
                        // and it takes a build slot away from exploratory
                        pairedTunnel = null;
                    }
                } else {
                    // random more reliable than closest ??
                    //pairedTunnel = mgr.selectInboundExploratoryTunnel(farEnd);
                    pairedTunnel = mgr.selectInboundTunnel();
                    if (pairedTunnel != null &&
                        pairedTunnel.getLength() <= 1 &&
                        mgr.getInboundSettings().getLength() > 0 &&
                        mgr.getInboundSettings().getLength() + mgr.getInboundSettings().getLengthVariance() > 0) {
                        // ditto
                        pairedTunnel = null;
                    }
                }
                if (pairedTunnel != null && log.shouldLog(Log.INFO))
                    log.info("Couldn't find a paired tunnel for " + cfg + ", using exploratory tunnel");
            }
        }
        if (pairedTunnel == null) {
            if (log.shouldLog(Log.WARN))
                log.warn("Tunnel build failed, as we couldn't find a paired tunnel for " + cfg);
            exec.buildComplete(cfg, pool);
            // Not even an exploratory tunnel? We are in big trouble.
            // Let's not spin through here too fast.
            // But don't let a client tunnel waiting for exploratories slow things down too much,
            // as there may be other tunnel pools who can build
            int ms = pool.getSettings().isExploratory() ? 250 : 25;
            try { Thread.sleep(ms); } catch (InterruptedException ie) {}
            return false;
        }
        
        //long beforeCreate = System.currentTimeMillis();
        TunnelBuildMessage msg = createTunnelBuildMessage(ctx, pool, cfg, pairedTunnel, exec);
        //long createTime = System.currentTimeMillis()-beforeCreate;
        if (msg == null) {
            if (log.shouldLog(Log.WARN))
                log.warn("Tunnel build failed, as we couldn't create the tunnel build message for " + cfg);
            exec.buildComplete(cfg, pool);
            return false;
        }
        
        //cfg.setPairedTunnel(pairedTunnel);
        
        //long beforeDispatch = System.currentTimeMillis();
        if (cfg.isInbound()) {
            if (log.shouldLog(Log.INFO))
                log.info("Sending the tunnel build request " + msg.getUniqueId() + " out the tunnel " + pairedTunnel + " to " 
                          + cfg.getPeer(0) + " for " + cfg + " waiting for the reply of "
                          + cfg.getReplyMessageId());
            // send it out a tunnel targetting the first hop
            // TODO - would be nice to have a TunnelBuildFirstHopFailJob queued if the
            // pairedTunnel is zero-hop, but no way to do that?
            ctx.tunnelDispatcher().dispatchOutbound(msg, pairedTunnel.getSendTunnelId(0), cfg.getPeer(0));
        } else {
            if (log.shouldLog(Log.INFO))
                log.info("Sending the tunnel build request directly to " + cfg.getPeer(1)
                          + " for " + cfg + " waiting for the reply of " + cfg.getReplyMessageId() 
                          + " with msgId=" + msg.getUniqueId());
            // send it directly to the first hop
            // Add some fuzz to the TBM expiration to make it harder to guess how many hops
            // or placement in the tunnel
            msg.setMessageExpiration(ctx.clock().now() + BUILD_MSG_TIMEOUT + ctx.random().nextLong(20*1000));
            // We set the OutNetMessage expiration much shorter, so that the
            // TunnelBuildFirstHopFailJob fires before the 13s build expiration.
            RouterInfo peer = ctx.netDb().lookupRouterInfoLocally(cfg.getPeer(1));
            if (peer == null) {
                if (log.shouldLog(Log.WARN))
                    log.warn("Could not find the next hop to send the outbound request to: " + cfg);
                exec.buildComplete(cfg, pool);
                return false;
            }
            OutNetMessage outMsg = new OutNetMessage(ctx, msg, ctx.clock().now() + FIRST_HOP_TIMEOUT, PRIORITY, peer);
            outMsg.setOnFailedSendJob(new TunnelBuildFirstHopFailJob(ctx, pool, cfg, exec));
            try {
                ctx.outNetMessagePool().add(outMsg);
            } catch (RuntimeException re) {
                log.error("failed sending build message", re);
                return false;
            }
        }
        //if (log.shouldLog(Log.DEBUG))
        //    log.debug("Tunnel build message " + msg.getUniqueId() + " created in " + createTime
        //              + "ms and dispatched in " + (System.currentTimeMillis()-beforeDispatch));
        return true;
    }

    /** @since 0.7.12 */
/****
we can assume everybody supports variable now...
keep this here for the next time we change the build protocol
    private static boolean supportsVariable(RouterContext ctx, Hash h) {
        RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(h);
        if (ri == null)
            return false;
        String v = ri.getVersion();
        return VersionComparator.comp(v, MIN_VARIABLE_VERSION) >= 0;
    }
****/

    /**
     *  If the tunnel is short enough, and everybody in the tunnel, and the
     *  OBEP or IBGW for the paired tunnel, all support the new variable-sized tunnel build message,
     *  then use that, otherwise the old 8-entry version.
     *  @return null on error
     */
    private static TunnelBuildMessage createTunnelBuildMessage(RouterContext ctx, TunnelPool pool,
                                                               PooledTunnelCreatorConfig cfg,
                                                               TunnelInfo pairedTunnel, BuildExecutor exec) {
        Log log = ctx.logManager().getLog(BuildRequestor.class);
        long replyTunnel = 0;
        Hash replyRouter;
        boolean useVariable = SEND_VARIABLE && cfg.getLength() <= MEDIUM_RECORDS;

        if (cfg.isInbound()) {
            //replyTunnel = 0; // as above
            replyRouter = ctx.routerHash();
/****
we can assume everybody supports variable now...
keep this here for the next time we change the build protocol
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
****/
        } else {
            replyTunnel = pairedTunnel.getReceiveTunnelId(0).getTunnelId();
            replyRouter = pairedTunnel.getPeer(0);
/****
we can assume everybody supports variable now
keep this here for the next time we change the build protocol
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
****/
        }

        // populate and encrypt the message
        TunnelBuildMessage msg;
        List<Integer> order;
        if (useVariable) {
            if (cfg.getLength() <= SHORT_RECORDS) {
                msg = new VariableTunnelBuildMessage(ctx, SHORT_RECORDS);
                order = new ArrayList<Integer>(SHORT_ORDER);
            } else {
                msg = new VariableTunnelBuildMessage(ctx, MEDIUM_RECORDS);
                order = new ArrayList<Integer>(MEDIUM_ORDER);
            }
        } else {
            msg = new TunnelBuildMessage(ctx);
            order = new ArrayList<Integer>(ORDER);
        }

        // This is in BuildExecutor.buildTunnel() now
        //long replyMessageId = ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE);
        //cfg.setReplyMessageId(replyMessageId);
        
        Collections.shuffle(order, ctx.random()); // randomized placement within the message
        cfg.setReplyOrder(order);
        
        if (log.shouldLog(Log.DEBUG))
            log.debug("Build order: " + order + " for " + cfg);
        
        for (int i = 0; i < msg.getRecordCount(); i++) {
            int hop = order.get(i).intValue();
            PublicKey key = null;
    
            if (BuildMessageGenerator.isBlank(cfg, hop)) {
                // erm, blank
            } else {
                Hash peer = cfg.getPeer(hop);
                RouterInfo peerInfo = ctx.netDb().lookupRouterInfoLocally(peer);
                if (peerInfo == null) {
                    if (log.shouldLog(Log.WARN))
                        log.warn("Peer selected for hop " + i + "/" + hop + " was not found locally: " 
                                  + peer + " for " + cfg);
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
        private final TunnelPool _pool;
        private final PooledTunnelCreatorConfig _cfg;
        private final BuildExecutor _exec;
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
