package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;

/**
 * Test the tunnel encryption - build a message as a gateway, pass it through
 * the sequence of participants (verifying the message along the way), and 
 * make sure it comes out the other side correctly.
 *
 */
public class TunnelProcessingTest {
    public void testTunnel() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        Log log = ctx.logManager().getLog(TunnelProcessingTest.class);
        if (true) {
            byte orig[] = new byte[16*1024];
            ctx.random().nextBytes(orig);
            GatewayTunnelConfig cfg = new GatewayTunnelConfig();
            for (int i = 0; i < GatewayMessage.HOPS; i++) {
                cfg.setSessionKey(i, ctx.keyGenerator().generateSessionKey());
                log.debug("key[" + i + "] = " + cfg.getSessionKey(i).toBase64());
            }
            testTunnel(ctx, orig, cfg);
        }
        if (false) {
            GatewayTunnelConfig cfg = new GatewayTunnelConfig();
            for (int i = 0; i < GatewayMessage.HOPS; i++) {
                SessionKey key = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
                cfg.setSessionKey(i, key);
                log.debug("key[" + i + "] = " + key.toBase64());
            }
            
            testTunnel(ctx, new byte[0], cfg);
        }
    }
    
    public void testTunnel(I2PAppContext ctx, byte orig[], GatewayTunnelConfig cfg) {
        Log log = ctx.logManager().getLog(TunnelProcessingTest.class);
        
        log.debug("H[orig] = " + ctx.sha().calculateHash(orig).toBase64());
        
        log.debug("\n\nEncrypting the payload");
        
        byte cur[] = new byte[orig.length];
        System.arraycopy(orig, 0, cur, 0, cur.length);
        GatewayMessage msg = new GatewayMessage(ctx);
        msg.setPayload(cur);
        msg.encrypt(cfg);
        int size = msg.getExportedSize();
        byte message[] = new byte[size];
        int exp = msg.export(message, 0);
        if (exp != size) throw new RuntimeException("Foo!");
        
        TunnelMessageProcessor proc = new TunnelMessageProcessor();
        for (int i = 1; i < GatewayMessage.HOPS; i++) {
            log.debug("\n\nUnwrapping step " + i);
            boolean ok = proc.unwrapMessage(ctx, message, cfg.getSessionKey(i));
            if (!ok) 
                log.error("Unwrap failed at step " + i);
            else
                log.info("** Unwrap succeeded at step " + i);
            boolean match = msg.compareChecksumBlock(ctx, message, i);
        }
        
        log.debug("\n\nVerifying the tunnel processing");
        
        for (int i = 0; i < orig.length; i++) {
            if (orig[i] != message[16 + i]) {
                log.error("Finished payload does not match at byte " + i + 
                          ctx.sha().calculateHash(message, 16, orig.length).toBase64());
                break;
            }
        }
        
        boolean ok = proc.verifyChecksum(ctx, message);
        if (!ok)
            log.error("Checksum could not be verified");
        else
            log.error("** Checksum verified");
    }
    
    public static void main(String args[]) {
        TunnelProcessingTest t = new TunnelProcessingTest();
        t.testTunnel();
    }
}
