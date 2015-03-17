package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Take a received tunnel message, verify that it isn't a 
 * duplicate, and translate it into what the next hop will 
 * want.  The hop processor works the same on all peers -
 * inbound and outbound participants, outbound endpoints,
 * and inbound gateways (with a small modification per 
 * InboundGatewayProcessor).  
 *
 */
class HopProcessor {
    protected final I2PAppContext _context;
    private final Log _log;
    protected final HopConfig _config;
    private final IVValidator _validator;
        
    /** helpful flag for debugging */
    static final boolean USE_ENCRYPTION = true;
    /**
     * as of i2p 0.6, the tunnel crypto will change by encrypting the IV both before 
     * and after using it at each hop so as to prevent a certain type of replay/confirmation 
     * attack.
     */
    static final boolean USE_DOUBLE_IV_ENCRYPTION = true;
    static final int IV_LENGTH = 16;
    private static final ByteCache _cache = ByteCache.getInstance(128, IV_LENGTH);
    
    /** @deprecated unused */
    public HopProcessor(I2PAppContext ctx, HopConfig config) {
        this(ctx, config, createValidator());
    }

    public HopProcessor(I2PAppContext ctx, HopConfig config, IVValidator validator) {
        _context = ctx;
        _log = ctx.logManager().getLog(HopProcessor.class);
        _config = config;
        _validator = validator;
    }
    
    /** @deprecated unused */
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
        
        boolean okIV = _validator.receiveIV(orig, offset, orig, offset + IV_LENGTH);
        if (!okIV) {
            if (_log.shouldLog(Log.WARN)) 
                _log.warn("Invalid IV, dropping at hop " + _config);
            return false;
        }
        
        if (_log.shouldLog(Log.DEBUG)) {
            //_log.debug("IV received: " + Base64.encode(iv));
            //_log.debug("Before:" + Base64.encode(orig, IV_LENGTH, orig.length - IV_LENGTH));
        }
        if (USE_ENCRYPTION) {
            if (USE_DOUBLE_IV_ENCRYPTION) 
                updateIV(orig, offset);
            encrypt(orig, offset, length);
            updateIV(orig, offset);
        }
        //if (_log.shouldLog(Log.DEBUG)) {
            //_log.debug("Data after processing: " + Base64.encode(orig, IV_LENGTH, orig.length - IV_LENGTH));
            //_log.debug("IV sent: " + Base64.encode(orig, 0, IV_LENGTH));
        //}
        return true;
    }
    
    private final void encrypt(byte data[], int offset, int length) {
        for (int off = offset + IV_LENGTH; off < length; off += IV_LENGTH) {
            //DataHelper.xor(data, off - IV_LENGTH, data, off, data, off, IV_LENGTH);
            for (int j = 0; j < IV_LENGTH; j++) {
                data[off + j] ^= data[(off - IV_LENGTH) + j];
            }
            _context.aes().encryptBlock(data, off, _config.getLayerKey(), data, off);
        }
    }
    
    private final void updateIV(byte orig[], int offset) {
        _context.aes().encryptBlock(orig, offset, _config.getIVKey(), orig, offset);
    }

    /**
     *  @since 0.8.12
     */
    @Override
    public String toString() {
        return getClass().getSimpleName() + " for " + _config;
    }
}
