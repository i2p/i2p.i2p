package net.i2p.router.tunnel;

import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.data.*;
import net.i2p.data.i2np.*;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *
 */
public class BuildMessageGenerator {
    // cached, rather than creating lots of temporary Integer objects whenever we build a tunnel
    static final Integer ORDER[] = new Integer[TunnelBuildMessage.RECORD_COUNT];
    static { for (int i = 0; i < ORDER.length; i++) ORDER[i] = new Integer(i); }
    
    /** return null if it is unable to find a router's public key (etc) */
    public TunnelBuildMessage createInbound(RouterContext ctx, TunnelCreatorConfig cfg) {
        return create(ctx, cfg, null, -1);
    }
    /** return null if it is unable to find a router's public key (etc) */
    public TunnelBuildMessage createOutbound(RouterContext ctx, TunnelCreatorConfig cfg, Hash replyRouter, long replyTunnel) {
        return create(ctx, cfg, replyRouter, replyTunnel);
    }
    
    private TunnelBuildMessage create(RouterContext ctx, TunnelCreatorConfig cfg, Hash replyRouter, long replyTunnel) {
        TunnelBuildMessage msg = new TunnelBuildMessage(ctx);
        List order = new ArrayList(ORDER.length);
        for (int i = 0; i < ORDER.length; i++) order.add(ORDER[i]);
        Collections.shuffle(order, ctx.random());
        for (int i = 0; i < ORDER.length; i++) {
            int hop = ((Integer)order.get(i)).intValue();
            Hash peer = cfg.getPeer(hop);
            RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(peer);
            if (ri == null)
                return null;
            createRecord(i, hop, msg, cfg, replyRouter, replyTunnel, ctx, ri.getIdentity().getPublicKey());
        }
        layeredEncrypt(ctx, msg, cfg, order);
        return msg;
    }
    
    /**
     * Place the asymmetrically encrypted record in the specified record slot, 
     * containing the hop's configuration (as well as the reply info, if it is an outbound endpoint)
     */
    public void createRecord(int recordNum, int hop, TunnelBuildMessage msg, TunnelCreatorConfig cfg, Hash replyRouter, long replyTunnel, I2PAppContext ctx, PublicKey peerKey) {
        Log log = ctx.logManager().getLog(getClass());
        BuildRequestRecord req = null;
        if ( (!cfg.isInbound()) && (hop + 1 == cfg.getLength()) ) //outbound endpoint
            req = createUnencryptedRecord(ctx, cfg, hop, replyRouter, replyTunnel);
        else
            req = createUnencryptedRecord(ctx, cfg, hop, null, -1);
        byte encrypted[] = new byte[TunnelBuildMessage.RECORD_SIZE];
        if (hop < cfg.getLength()) {
            Hash peer = cfg.getPeer(hop);
            if (peerKey == null) 
                throw new RuntimeException("hop = " + hop + " recordNum = " + recordNum + " len = " + cfg.getLength());
            //if (log.shouldLog(Log.DEBUG))
            //    log.debug("Record " + recordNum + "/" + hop + ": unencrypted = " + Base64.encode(req.getData().getData()));
            req.encryptRecord(ctx, peerKey, peer, encrypted, 0);
            //if (log.shouldLog(Log.DEBUG))
            //    log.debug("Record " + recordNum + "/" + hop + ": encrypted   = " + Base64.encode(encrypted));
        } else {
            ctx.random().nextBytes(encrypted);
        }
        msg.setRecord(recordNum, new ByteArray(encrypted));
    }
    
    private BuildRequestRecord createUnencryptedRecord(I2PAppContext ctx, TunnelCreatorConfig cfg, int hop, Hash replyRouter, long replyTunnel) {
        if (hop < cfg.getLength()) {
            // ok, now lets fill in some data
            HopConfig hopConfig = cfg.getConfig(hop);
            Hash peer = cfg.getPeer(hop);
            long recvTunnelId = hopConfig.getReceiveTunnel().getTunnelId();
            long nextTunnelId = -1;
            Hash nextPeer = null;
            if (hop + 1 < cfg.getLength()) {
                nextTunnelId = cfg.getConfig(hop+1).getReceiveTunnel().getTunnelId();
                nextPeer = cfg.getPeer(hop+1);
            } else {
                if ( (replyTunnel >= 0) && (replyRouter != null) ) {
                    nextTunnelId = replyTunnel;
                    nextPeer = replyRouter;
                } else {
                    // inbound endpoint (aka creator)
                    nextTunnelId = 0;
                    nextPeer = peer; // self
                }
            }
            SessionKey layerKey = hopConfig.getLayerKey();
            SessionKey ivKey = hopConfig.getIVKey();
            SessionKey replyKey = hopConfig.getReplyKey();
            byte iv[] = hopConfig.getReplyIV().getData();
            if ( (iv == null) || (iv.length != BuildRequestRecord.IV_SIZE) ) {
                iv = new byte[BuildRequestRecord.IV_SIZE];
                ctx.random().nextBytes(iv);
                hopConfig.getReplyIV().setData(iv);
            }
            boolean isInGW = (cfg.isInbound() && (hop == 0));
            boolean isOutEnd = (!cfg.isInbound() && (hop + 1 >= cfg.getLength()));
            
            BuildRequestRecord rec= new BuildRequestRecord();
            rec.createRecord(ctx, recvTunnelId, peer, nextTunnelId, nextPeer, layerKey, ivKey, replyKey, 
                             iv, isInGW, isOutEnd);
                             
            return rec;
        } else {
            return null;
        }
    }
    
    /**
     * Encrypt the records so their hop ident is visible at the appropriate times
     * @param order list of hop #s as Integers.  For instance, if (order.get(1) is 4), it is peer cfg.getPeer(4)
     */
    public void layeredEncrypt(I2PAppContext ctx, TunnelBuildMessage msg, TunnelCreatorConfig cfg, List order) {
        // encrypt the records so that the right elements will be visible at the right time
        for (int i = 0; i < TunnelBuildMessage.RECORD_COUNT; i++) {
            ByteArray rec = msg.getRecord(i);
            Integer hopNum = (Integer)order.get(i);
            int hop = hopNum.intValue();
            if (hop >= cfg.getLength())
                continue; // no need to encrypt it, as its random
            // ok, now decrypt the record with all of the reply keys from cfg.getConfig(0) through hop-1
            for (int j = hop-1; j >= 0; j--) {
                HopConfig hopConfig = cfg.getConfig(j);
                SessionKey key = hopConfig.getReplyKey();
                byte iv[] = hopConfig.getReplyIV().getData();
                int off = rec.getOffset();
                ctx.aes().decrypt(rec.getData(), off, rec.getData(), off, key, iv, TunnelBuildMessage.RECORD_SIZE);
            }
        }
    }
}
