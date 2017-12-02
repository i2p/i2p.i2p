package net.i2p.router.tunnel;

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
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.BuildRequestRecord;
import net.i2p.data.i2np.BuildResponseRecord;
import net.i2p.data.i2np.EncryptedBuildRecord;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.data.i2np.TunnelBuildReplyMessage;
import net.i2p.util.Log;

/**
 * Simple test to create an encrypted TunnelBuildMessage, decrypt its layers (as it would be
 * during transmission), inject replies, then handle the TunnelBuildReplyMessage (unwrapping
 * the reply encryption and reading the replies).
 * 
 * ===
 * Update 1/5/2013 :
 * This test is renamed so it does not match the JUnit wildcard.
 * There is something wrong with the decryption check; it doesn't look like the test takes
 * into consideration the re-encryption of the records in the TunnelBuildMessage.
 * Most probably the test will have to be re-written from scratch.
 * --zab
 */
public class BuildMessageTestStandalone extends TestCase {
    private Hash _peers[];
    private PrivateKey _privKeys[];
    private PublicKey _pubKeys[];
    private Hash _replyRouter;
    private long _replyTunnel;
    
    public void testBuildMessage() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        Log log = ctx.logManager().getLog(getClass());
        
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
                  "\n================================================================");
        
        // now msg is fully encrypted, so lets go through the hops, decrypting and replying
        // as necessary
        
        BuildMessageProcessor proc = new BuildMessageProcessor(ctx);
        for (int i = 0; i < cfg.getLength(); i++) {
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

            EncryptedBuildRecord reply = BuildResponseRecord.create(ctx, 0, req.readReplyKey(), req.readReplyIV(), -1);
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
        for (int i = 0; i < cfg.getLength(); i++) {
            Hash peer = cfg.getPeer(i);
            int record = order.get(i).intValue();
            if (statuses[record] != 0)
                allAgree = false;
            //else
            //    penalize peer according to the rejection cause    
        }
        
        log.debug("\n================================================================" +
                  "\nAll peers agree? " + allAgree + 
                  "\n================================================================");
    }
    
    private static final List<Integer> pickOrder() {
        // pseudorandom, yet consistent (so we can be repeatable)
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
        
        TunnelCreatorConfig cfg = new TunnelCreatorConfig(null, _peers.length, false);
        long now = ctx.clock().now();
        // peers[] is ordered endpoint first, but cfg.getPeer() is ordered gateway first
        for (int i = 0; i < _peers.length; i++) {
            cfg.setPeer(i, _peers[i]);
            HopConfig hop = cfg.getConfig(i);
            hop.setExpiration(now+10*60*1000);
            hop.setIVKey(ctx.keyGenerator().generateSessionKey());
            hop.setLayerKey(ctx.keyGenerator().generateSessionKey());
            hop.setReplyKey(ctx.keyGenerator().generateSessionKey());
            byte iv[] = new byte[BuildRequestRecord.IV_SIZE];
            Arrays.fill(iv, (byte)i); // consistent for repeatability
            hop.setReplyIV(iv);
            hop.setReceiveTunnelId(new TunnelId(i+1));
        }
        return cfg;
    }
}
