package net.i2p.router.tunnel;

import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.data.EmptyProperties;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.BuildRequestRecord;
import net.i2p.data.i2np.EncryptedBuildRecord;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.router.RouterContext;

/**
 *  Fill in the encrypted BuildRequestRecords in a TunnelBuildMessage
 */
public abstract class BuildMessageGenerator {
    
    /**
     * Place the asymmetrically encrypted record in the specified record slot, 
     * containing the hop's configuration (as well as the reply info, if it is an outbound endpoint)
     *
     * @param msg out parameter
     * @param peerKey Encrypt using this key.
     *                If null, replyRouter and replyTunnel are ignored,
     *                and the entire record is filled with random data
     * @throws IllegalArgumentException if hop bigger than config
     */
    public static void createRecord(int recordNum, int hop, TunnelBuildMessage msg,
                                    TunnelCreatorConfig cfg, Hash replyRouter,
                                    long replyTunnel, RouterContext ctx, PublicKey peerKey) {
        EncryptedBuildRecord erec;
        if (peerKey != null) {
            boolean isEC = peerKey.getType() == EncType.ECIES_X25519;
            BuildRequestRecord req;
            if ( (!cfg.isInbound()) && (hop + 1 == cfg.getLength()) ) //outbound endpoint
                req = createUnencryptedRecord(ctx, cfg, hop, replyRouter, replyTunnel, isEC);
            else
                req = createUnencryptedRecord(ctx, cfg, hop, null, -1, isEC);
            if (req == null)
                throw new IllegalArgumentException("hop bigger than config");
            Hash peer = cfg.getPeer(hop);
            if (isEC) {
                erec = req.encryptECIESRecord(ctx, peerKey, peer);
                cfg.setChaChaReplyKeys(hop, req.getChaChaReplyKey(), req.getChaChaReplyAD());
            } else {
                erec = req.encryptRecord(ctx, peerKey, peer);
            }
        } else {
            byte encrypted[] = new byte[TunnelBuildMessage.RECORD_SIZE];
            if (cfg.isInbound() && hop + 1 == cfg.getLength()) { // IBEP
                System.arraycopy(cfg.getPeer(hop).getData(), 0, encrypted, 0, BuildRequestRecord.PEER_SIZE);
                ctx.random().nextBytes(encrypted, BuildRequestRecord.PEER_SIZE, TunnelBuildMessage.RECORD_SIZE - BuildRequestRecord.PEER_SIZE);
                byte[] h = new byte[Hash.HASH_LENGTH];
                ctx.sha().calculateHash(encrypted, 0, TunnelBuildMessage.RECORD_SIZE, h, 0);
                cfg.setBlankHash(new Hash(h));
            } else {
                ctx.random().nextBytes(encrypted);
            }
            erec = new EncryptedBuildRecord(encrypted);
        }
        msg.setRecord(recordNum, erec);
    }
    
    /**
     *  Returns null if hop >= cfg.length
     */
    private static BuildRequestRecord createUnencryptedRecord(I2PAppContext ctx, TunnelCreatorConfig cfg, int hop,
                                                              Hash replyRouter, long replyTunnel, boolean isEC) {
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
            SessionKey replyKey = cfg.getAESReplyKey(hop);
            byte iv[] = cfg.getAESReplyIV(hop);
            if (iv == null) {
                throw new IllegalStateException();
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
            BuildRequestRecord rec;
            if (isEC) {
                // TODO pass properties from cfg
                rec = new BuildRequestRecord(ctx, recvTunnelId, nextTunnelId, nextPeer,
                                             nextMsgId, layerKey, ivKey, replyKey, 
                                             iv, isInGW, isOutEnd, EmptyProperties.INSTANCE);
            } else {
                rec = new BuildRequestRecord(ctx, recvTunnelId, peer, nextTunnelId, nextPeer,
                                             nextMsgId, layerKey, ivKey, replyKey, 
                                             iv, isInGW, isOutEnd);
            }
            return rec;
        } else {
            return null;
        }
    }
    
    /**
     * Encrypt the records so their hop ident is visible at the appropriate times.
     *
     * Note that this layer-encrypts the build records for the message in-place.
     * Only call this once for a given message.
     *
     * @param order list of hop #s as Integers.  For instance, if (order.get(1) is 4), it is peer cfg.getPeer(4)
     */
    public static void layeredEncrypt(I2PAppContext ctx, TunnelBuildMessage msg,
                                      TunnelCreatorConfig cfg, List<Integer> order) {
        // encrypt the records so that the right elements will be visible at the right time
        for (int i = 0; i < msg.getRecordCount(); i++) {
            EncryptedBuildRecord rec = msg.getRecord(i);
            Integer hopNum = order.get(i);
            int hop = hopNum.intValue();
            if ((isBlank(cfg, hop) && !(cfg.isInbound() && hop + 1 == cfg.getLength())) ||
                (!cfg.isInbound() && hop == 1)) {
                continue;
            }
            // ok, now decrypt the record with all of the reply keys from cfg.getConfig(0) through hop-1
            int stop = (cfg.isInbound() ? 0 : 1);
            for (int j = hop-1; j >= stop; j--) {
                SessionKey key = cfg.getAESReplyKey(j);
                byte iv[] = cfg.getAESReplyIV(j);
                // corrupts the SDS
                ctx.aes().decrypt(rec.getData(), 0, rec.getData(), 0, key, iv, TunnelBuildMessage.RECORD_SIZE);
            }
        }
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
