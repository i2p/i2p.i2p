package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.BuildRequestRecord;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  Fill in the encrypted BuildRequestRecords in a TunnelBuildMessage
 */
public abstract class BuildMessageGenerator {
    
    /** return null if it is unable to find a router's public key (etc) */
/****
    public TunnelBuildMessage createInbound(RouterContext ctx, TunnelCreatorConfig cfg) {
        return create(ctx, cfg, null, -1);
    }
****/

    /** return null if it is unable to find a router's public key (etc) */
/****
    public TunnelBuildMessage createOutbound(RouterContext ctx, TunnelCreatorConfig cfg, Hash replyRouter, long replyTunnel) {
        return create(ctx, cfg, replyRouter, replyTunnel);
    }
****/
    
/****
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
****/
    
    /**
     * Place the asymmetrically encrypted record in the specified record slot, 
     * containing the hop's configuration (as well as the reply info, if it is an outbound endpoint)
     *
     * @param msg out parameter
     */
    public static void createRecord(int recordNum, int hop, TunnelBuildMessage msg, TunnelCreatorConfig cfg, Hash replyRouter, long replyTunnel, I2PAppContext ctx, PublicKey peerKey) {
        byte encrypted[] = new byte[TunnelBuildMessage.RECORD_SIZE];
        //Log log = ctx.logManager().getLog(BuildMessageGenerator.class);
        if (peerKey != null) {
            BuildRequestRecord req = null;
            if ( (!cfg.isInbound()) && (hop + 1 == cfg.getLength()) ) //outbound endpoint
                req = createUnencryptedRecord(ctx, cfg, hop, replyRouter, replyTunnel);
            else
                req = createUnencryptedRecord(ctx, cfg, hop, null, -1);
            Hash peer = cfg.getPeer(hop);
            //if (log.shouldLog(Log.DEBUG))
            //    log.debug("Record " + recordNum + "/" + hop + "/" + peer.toBase64() 
            //              + ": unencrypted = " + Base64.encode(req.getData().getData()));
            req.encryptRecord(ctx, peerKey, peer, encrypted, 0);
            //if (log.shouldLog(Log.DEBUG))
            //    log.debug("Record " + recordNum + "/" + hop + ": encrypted   = " + Base64.encode(encrypted));
        } else {
            //if (log.shouldLog(Log.DEBUG))
            //    log.debug("Record " + recordNum + "/" + hop + "/ is blank/random");
            ctx.random().nextBytes(encrypted);
        }
        msg.setRecord(recordNum, new ByteArray(encrypted));
    }
    
    private static BuildRequestRecord createUnencryptedRecord(I2PAppContext ctx, TunnelCreatorConfig cfg, int hop, Hash replyRouter, long replyTunnel) {
        //Log log = ctx.logManager().getLog(BuildMessageGenerator.class);
        if (hop < cfg.getLength()) {
            // ok, now lets fill in some data
            HopConfig hopConfig = cfg.getConfig(hop);
            Hash peer = cfg.getPeer(hop);
            long recvTunnelId = -1;
            if (cfg.isInbound() || (hop > 0))
                recvTunnelId = hopConfig.getReceiveTunnel().getTunnelId();
            else
                recvTunnelId = 0;
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
            
            long nextMsgId = -1;
            if (isOutEnd || (cfg.isInbound() && (hop + 2 >= cfg.getLength())) ) {
                nextMsgId = cfg.getReplyMessageId();
            } else {
                // dont care about these intermediary hops
                nextMsgId = ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE);
            }
            
            //if (log.shouldLog(Log.DEBUG))
            //    log.debug("Hop " + hop + " has the next message ID of " + nextMsgId + " for " + cfg 
            //              + " with replyKey " + replyKey.toBase64() + " and replyIV " + Base64.encode(iv));
            
            BuildRequestRecord rec= new BuildRequestRecord();
            rec.createRecord(ctx, recvTunnelId, peer, nextTunnelId, nextPeer, nextMsgId, layerKey, ivKey, replyKey, 
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
    public static void layeredEncrypt(I2PAppContext ctx, TunnelBuildMessage msg, TunnelCreatorConfig cfg, List order) {
        //Log log = ctx.logManager().getLog(BuildMessageGenerator.class);
        // encrypt the records so that the right elements will be visible at the right time
        for (int i = 0; i < msg.getRecordCount(); i++) {
            ByteArray rec = msg.getRecord(i);
            Integer hopNum = (Integer)order.get(i);
            int hop = hopNum.intValue();
            if ( (isBlank(cfg, hop)) || (!cfg.isInbound() && hop == 1) ) {
                //if (log.shouldLog(Log.DEBUG))
                //    log.debug(msg.getUniqueId() + ": not pre-decrypting record " + i + "/" + hop + " for " + cfg);
                continue;
            }
            //if (log.shouldLog(Log.DEBUG))
            //    log.debug(msg.getUniqueId() + ": pre-decrypting record " + i + "/" + hop + " for " + cfg);
            // ok, now decrypt the record with all of the reply keys from cfg.getConfig(0) through hop-1
            int stop = (cfg.isInbound() ? 0 : 1);
            for (int j = hop-1; j >= stop; j--) {
                HopConfig hopConfig = cfg.getConfig(j);
                SessionKey key = hopConfig.getReplyKey();
                byte iv[] = hopConfig.getReplyIV().getData();
                int off = rec.getOffset();
                //if (log.shouldLog(Log.DEBUG))
                //    log.debug(msg.getUniqueId() + ": pre-decrypting record " + i + "/" + hop + " for " + cfg 
                //              + " with " + key.toBase64() + "/" + Base64.encode(iv));
                ctx.aes().decrypt(rec.getData(), off, rec.getData(), off, key, iv, TunnelBuildMessage.RECORD_SIZE);
            }
        }
        //if (log.shouldLog(Log.DEBUG))
        //    log.debug(msg.getUniqueId() + ": done pre-decrypting all records for " + cfg);
    }
    
    public static boolean isBlank(TunnelCreatorConfig cfg, int hop) {
        if (cfg.isInbound()) {
            if (hop + 1 >= cfg.getLength())
                return true;
            else
                return false;
        } else {
            if (hop == 0)
                return true;
            else if (hop >= cfg.getLength())
                return true;
            else
                return false;
        }
    }
}
