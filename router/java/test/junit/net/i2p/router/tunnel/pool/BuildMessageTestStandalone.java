package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import junit.framework.TestCase;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.BuildRequestRecord;
import net.i2p.data.i2np.BuildResponseRecord;
import net.i2p.data.i2np.EncryptedBuildRecord;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.data.i2np.TunnelBuildReplyMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TCConfig;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.util.Log;

/**
 * Simple test to create an encrypted TunnelBuildMessage, decrypt its layers (as it would be
 * during transmission), inject replies, then handle the TunnelBuildReplyMessage (unwrapping
 * the reply encryption and reading the replies).
 * 
 * ===
 * Update 1/5/2013 :
 * This test is renamed so it does not match the JUnit wildcard.
 */
public class BuildMessageTestStandalone extends TestCase {
    private Hash _peers[];
    private PrivateKey _privKeys[];
    private PublicKey _pubKeys[];
    private Hash _replyRouter;
    private long _replyTunnel;
    
    public void testBuildMessage() {
        RouterContext ctx = new RouterContext(null);
        x_testBuildMessage(ctx);
    }

    private void x_testBuildMessage(RouterContext ctx) {
        Log log = ctx.logManager().getLog(getClass());
        // set our keys to avoid NPE
        Object[] kp = ctx.keyGenerator().generatePKIKeypair();
        PublicKey k1 = (PublicKey) kp[0];
        PrivateKey k2 = (PrivateKey) kp[1];
        kp = ctx.keyGenerator().generateSigningKeypair();
        SigningPublicKey k3 = (SigningPublicKey) kp[0];
        SigningPrivateKey k4 = (SigningPrivateKey) kp[1];
        ctx.keyManager().setKeys(k1, k2, k3, k4);
        
        List<Integer> order = pickOrder();
        
        TunnelCreatorConfig cfg = createConfig(ctx);
        _replyRouter = new Hash();
        byte h[] = new byte[Hash.HASH_LENGTH];
        Arrays.fill(h, (byte)0xFF);
        _replyRouter.setData(h);
        _replyTunnel = 42;
        
        // populate and encrypt the message
        TunnelBuildMessage msg = new TunnelBuildMessage(ctx);
        for (int i = 0; i < order.size(); i++) {
            int hop = order.get(i).intValue();
            PublicKey key = null;
            if (hop < _pubKeys.length)
                key = _pubKeys[hop];
            BuildMessageGenerator.createRecord(i, hop, msg, cfg, _replyRouter, _replyTunnel, ctx, key);
        }
        BuildMessageGenerator.layeredEncrypt(ctx, msg, cfg, order);
        
        log.debug("\n================================================================" +
                  "\nMessage fully encrypted" + 
                  "\n" + cfg +
                  "\n================================================================");
        
        // now msg is fully encrypted, so lets go through the hops, decrypting and replying
        // as necessary
        
        BuildMessageProcessor proc = new BuildMessageProcessor(ctx);
        // skip cfg(0) which is the gateway (us)
        for (int i = 1; i < cfg.getLength(); i++) {
            // this not only decrypts the current hop's record, but encrypts the other records
            // with the reply key
            BuildRequestRecord req = proc.decrypt(msg, _peers[i], _privKeys[i]);
            // If false, no records matched the _peers[i], or the decryption failed
            assertTrue("foo @ " + i, req != null);
            long ourId = req.readReceiveTunnelId();
            byte replyIV[] = req.readReplyIV();
            long nextId = req.readNextTunnelId();
            Hash nextPeer = req.readNextIdentity();
            boolean isInGW = req.readIsInboundGateway();
            boolean isOutEnd = req.readIsOutboundEndpoint();
            long time = req.readRequestTime();
            long now = (ctx.clock().now() / (60l*60l*1000l)) * (60*60*1000);
            int ourSlot = -1;

            EncryptedBuildRecord reply = BuildResponseRecord.create(ctx, 0, req.readReplyKey(), replyIV, i);
            for (int j = 0; j < TunnelBuildMessage.MAX_RECORD_COUNT; j++) {
                if (msg.getRecord(j) == null) {
                    ourSlot = j;
                    msg.setRecord(j, reply);
                    break;
                }
            }
            
            log.debug("Read slot " + ourSlot + " containing hop " + i + " @ " + _peers[i].toBase64() 
                      + " receives on " + ourId 
                      + " w/ replyIV " + Base64.encode(replyIV) + " sending to " + nextId
                      + " on " + nextPeer.toBase64()
                      + " inGW? " + isInGW + " outEnd? " + isOutEnd + " time difference " + (now-time));
        }
        
        
        log.debug("\n================================================================" +
                  "\nAll hops traversed and replies gathered" +
                  "\n================================================================");
        
        // now all of the replies are populated, toss 'em into a reply message and handle it
        TunnelBuildReplyMessage reply = new TunnelBuildReplyMessage(ctx);
        for (int i = 0; i < TunnelBuildMessage.MAX_RECORD_COUNT; i++)
            reply.setRecord(i, msg.getRecord(i));

        int statuses[] = (new BuildReplyHandler(ctx)).decrypt(reply, cfg, order);
        if (statuses == null) throw new RuntimeException("bar");
        boolean allAgree = true;
        for (int i = 1; i < cfg.getLength(); i++) {
            Hash peer = cfg.getPeer(i);
            int record = order.get(i).intValue();
            if (statuses[record] != 0)
                allAgree = false;
        }
        
        log.debug("\n================================================================" +
                  "\nAll peers agree? " + allAgree + 
                  "\n================================================================");
    }
    
    private static final List<Integer> pickOrder() {
        // pseudorandom, yet consistent (so we can be repeatable)
        // slot -> hop
        // build slots for hops 1-3 are 4,0,5
        List<Integer> rv = new ArrayList<Integer>(8);
        rv.add(new Integer(2));
        rv.add(new Integer(4));
        rv.add(new Integer(6));
        rv.add(new Integer(0));
        rv.add(new Integer(1));
        rv.add(new Integer(3));
        rv.add(new Integer(5));
        rv.add(new Integer(7));
        return rv;
    }

    private TunnelCreatorConfig createConfig(I2PAppContext ctx) {
        return configOutbound(ctx);
    }

    /**
     *  This creates a 3-hop (4 entries in the config) outbound tunnel.
     *  The first entry in the config is the gateway (us),
     *  and is mostly ignored.
     */
    private TunnelCreatorConfig configOutbound(I2PAppContext ctx) {
        _peers = new Hash[4];
        _pubKeys = new PublicKey[_peers.length];
        _privKeys = new PrivateKey[_peers.length];
        for (int i = 0; i < _peers.length; i++) {
            byte buf[] = new byte[Hash.HASH_LENGTH];
            Arrays.fill(buf, (byte)i); // consistent for repeatability
            Hash h = new Hash(buf);
            _peers[i] = h;
            Object kp[] = ctx.keyGenerator().generatePKIKeypair();
            _pubKeys[i] = (PublicKey)kp[0];
            _privKeys[i] = (PrivateKey)kp[1];
        }
        
        TunnelCreatorConfig cfg = new TCConfig(null, _peers.length, false);
        long now = ctx.clock().now();
        // peers[] is ordered gateway first (unlike in production code)
        for (int i = 0; i < _peers.length; i++) {
            cfg.setPeer(i, _peers[i]);
            HopConfig hop = cfg.getConfig(i);
            hop.setCreation(now);
            hop.setExpiration(now+10*60*1000);
            hop.setIVKey(ctx.keyGenerator().generateSessionKey());
            hop.setLayerKey(ctx.keyGenerator().generateSessionKey());
            byte iv[] = new byte[BuildRequestRecord.IV_SIZE];
            Arrays.fill(iv, (byte)i); // consistent for repeatability
            cfg.setAESReplyKeys(i, ctx.keyGenerator().generateSessionKey(), iv);
            hop.setReceiveTunnelId(new TunnelId(i+1));
        }
        return cfg;
    }

    /**
     *  @since 0.9.51
     */
    public static void main(String[] args) {
        BuildMessageTestStandalone test = new BuildMessageTestStandalone();
        Router r = new Router();
        RouterContext ctx = r.getContext();
        ctx.initAll();
        try {
            test.x_testBuildMessage(ctx);
        } finally {
            ctx.logManager().flush();
        }
    }
}
