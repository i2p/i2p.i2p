package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Take a received tunnel message, verify that it isn't a 
 * duplicate, and translate it into what the next hop will 
 * want.  The hop processor works the same on all peers -
 * inbound and outbound participants, outbound endpoints,
 * and inbound gateways (with a small modification per 
 * InbuondGatewayProcessor).  
 *
 */
public class HopProcessor {
    protected I2PAppContext _context;
    private Log _log;
    protected HopConfig _config;
    private IVValidator _validator;
        
    /** helpful flag for debugging */
    static final boolean USE_ENCRYPTION = true;
    static final int IV_LENGTH = 16;
    private static final ByteCache _cache = ByteCache.getInstance(128, IV_LENGTH);
    
    public HopProcessor(I2PAppContext ctx, HopConfig config) {
        this(ctx, config, createValidator());
    }
    public HopProcessor(I2PAppContext ctx, HopConfig config, IVValidator validator) {
        _context = ctx;
        _log = ctx.logManager().getLog(HopProcessor.class);
        _config = config;
        _validator = validator;
    }
    
    protected static IVValidator createValidator() { 
        // yeah, we'll use an O(1) validator later (e.g. bloom filter)
        return new HashSetIVValidator();
    }
    
    /**
     * Process the data for the current hop, overwriting the original data with
     * what should be sent to the next peer.  This also validates the previous 
     * peer and the IV, making sure its not a repeat and not a loop.
     *
     * @param orig IV+data of the message
     * @param offset index into orig where the IV begins
     * @param length how long after the offset does the message go for?
     * @param prev previous hop in the tunnel, or null if we are the gateway
     * @return true if the message was updated and valid, false if it was not.
     */
    public boolean process(byte orig[], int offset, int length, Hash prev) {
        // prev is null on gateways
        if (prev != null) {
            if (_config.getReceiveFrom() == null)
                _config.setReceiveFrom(prev);
            if (!_config.getReceiveFrom().equals(prev)) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Invalid previous peer - attempted hostile loop?  from " + prev 
                               + ", expected " + _config.getReceiveFrom());
                return false;
            }
        }
        
        ByteArray ba = _cache.acquire();
        byte iv[] = ba.getData(); // new byte[IV_LENGTH];
        System.arraycopy(orig, offset, iv, 0, IV_LENGTH);
        boolean okIV = _validator.receiveIV(iv);
        if (!okIV) {
            if (_log.shouldLog(Log.WARN)) 
                _log.warn("Invalid IV received on tunnel " + _config.getReceiveTunnelId());
            return false;
        }
        
        if (_log.shouldLog(Log.DEBUG)) {
            //_log.debug("IV received: " + Base64.encode(iv));
            //_log.debug("Before:" + Base64.encode(orig, IV_LENGTH, orig.length - IV_LENGTH));
        }
        if (USE_ENCRYPTION) {
            encrypt(orig, offset, length);
            updateIV(orig, offset);
        }
        if (_log.shouldLog(Log.DEBUG)) {
            //_log.debug("Data after processing: " + Base64.encode(orig, IV_LENGTH, orig.length - IV_LENGTH));
            //_log.debug("IV sent: " + Base64.encode(orig, 0, IV_LENGTH));
        }
        _cache.release(ba);
        return true;
    }
    
    private final void encrypt(byte data[], int offset, int length) {
        for (int off = offset + IV_LENGTH; off < length; off += IV_LENGTH) {
            DataHelper.xor(data, off - IV_LENGTH, data, off, data, off, IV_LENGTH);
            _context.aes().encryptBlock(data, off, _config.getLayerKey(), data, off);
        }
    }
    
    private final void updateIV(byte orig[], int offset) {
        _context.aes().encryptBlock(orig, offset, _config.getIVKey(), orig, offset);
    }
}
