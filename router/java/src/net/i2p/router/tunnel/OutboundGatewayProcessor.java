package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Turn the preprocessed tunnel data into something that can be delivered to the
 * first hop in the tunnel.  The crypto used in this class is also used by the
 * InboundEndpointProcessor, as its the same 'undo' function of the tunnel crypto.
 *
 */
public class OutboundGatewayProcessor {
    private I2PAppContext _context;
    private Log _log;
    private TunnelCreatorConfig _config;
        
    static final boolean USE_ENCRYPTION = HopProcessor.USE_ENCRYPTION;

    public OutboundGatewayProcessor(I2PAppContext ctx, TunnelCreatorConfig cfg) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundGatewayProcessor.class);
        _config = cfg;
    }
    
    /**
     * Since we are the outbound gateway, pick a random IV and wrap the preprocessed 
     * data so that it will be exposed at the endpoint.
     *
     * @param orig original data with an extra 16 byte IV prepended.
     * @param offset index into the array where the extra 16 bytes (IV) begins
     * @param length how much of orig can we write to (must be a multiple of 16).
     */
    public void process(byte orig[], int offset, int length) {
        byte iv[] = new byte[HopProcessor.IV_LENGTH];
        //_context.random().nextBytes(iv);
        //System.arraycopy(iv, 0, orig, offset, HopProcessor.IV_LENGTH);
        System.arraycopy(orig, offset, iv, 0, HopProcessor.IV_LENGTH);
        
        if (_log.shouldLog(Log.DEBUG)) {
            //_log.debug("Original random IV: " + Base64.encode(iv));
            //_log.debug("data:  " + Base64.encode(orig, iv.length, length - iv.length));
        }
        if (USE_ENCRYPTION)
            decrypt(_context, _config, iv, orig, offset, length);
    }
    
    /**
     * Undo the crypto that the various layers in the tunnel added.  This is used
     * by both the outbound gateway (preemptively undoing the crypto peers will add)
     * and by the inbound endpoint.
     *
     */
    static void decrypt(I2PAppContext ctx, TunnelCreatorConfig cfg, byte iv[], byte orig[], int offset, int length) {
        Log log = ctx.logManager().getLog(OutboundGatewayProcessor.class);
        byte cur[] = new byte[HopProcessor.IV_LENGTH]; // so we dont malloc
        for (int i = cfg.getLength()-1; i >= 0; i--) {
            decrypt(ctx, iv, orig, offset, length, cur, cfg.getConfig(i));
            if (log.shouldLog(Log.DEBUG)) {
                //log.debug("IV at hop " + i + ": " + Base64.encode(orig, offset, HopProcessor.IV_LENGTH));
                //log.debug("hop " + i + ": " + Base64.encode(orig, offset + HopProcessor.IV_LENGTH, length - HopProcessor.IV_LENGTH));
            }
        }
    }
    
    private static void decrypt(I2PAppContext ctx, byte iv[], byte orig[], int offset, int length, byte cur[], HopConfig config) {
        // update the IV for the previous (next?) hop
        ctx.aes().decryptBlock(orig, offset, config.getIVKey(), orig, offset);
        
        int numBlocks = (length - HopProcessor.IV_LENGTH) / HopProcessor.IV_LENGTH;
        
        // prev == previous encrypted block (or IV for the first block)
        byte prev[] = iv;
        System.arraycopy(orig, offset, prev, 0, HopProcessor.IV_LENGTH);
        //_log.debug("IV at curHop: " + Base64.encode(iv));
        
        //decrypt the whole row
        for (int i = 0; i < numBlocks; i++) {
            int off = (i + 1) * HopProcessor.IV_LENGTH + offset;
        
            System.arraycopy(orig, off, cur, 0, HopProcessor.IV_LENGTH);
            ctx.aes().decryptBlock(orig, off, config.getLayerKey(), orig, off);
            DataHelper.xor(prev, 0, orig, off, orig, off, HopProcessor.IV_LENGTH);
            byte xf[] = prev;
            prev = cur;
            cur = xf;
        }
    }
}
