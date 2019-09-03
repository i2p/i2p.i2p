package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.crypto.AESEngine;
import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
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
    //static final boolean USE_ENCRYPTION = true;
    /**
     * as of i2p 0.6, the tunnel crypto changed  to encrypt the IV both before 
     * and after using it at each hop so as to prevent a certain type of replay/confirmation 
     * attack.
     *
     * See: http://osdir.com/ml/network.i2p/2005-07/msg00031.html
     */
    static final int IV_LENGTH = 16;

    /**
     *  @deprecated used only by unit tests
     */
    @Deprecated
    HopProcessor(I2PAppContext ctx, HopConfig config) {
        this(ctx, config, createValidator());
    }
    
    public HopProcessor(I2PAppContext ctx, HopConfig config, IVValidator validator) {
        _context = ctx;
        _log = ctx.logManager().getLog(HopProcessor.class);
        _config = config;
        _validator = validator;
    }

    /**
     *  @deprecated used only by unit test constructor
     */
    @Deprecated
    private static IVValidator createValidator() { 
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
     * @param length how long after the offset does the IV+message go for?
     *               Should always be 1024 bytes.
     * @param prev previous hop in the tunnel, or null if we are the gateway
     * @return true if the message was updated and valid, false if it was not.
     */
    public boolean process(byte orig[], int offset, int length, Hash prev) {
        // prev is null on gateways
        if (prev != null) {
            if (_config.getReceiveFrom() == null) {
                _config.setReceiveFrom(prev);
            } else if (!_config.getReceiveFrom().equals(prev)) {
                // shouldn't happen now that we have good dup ID detection in BuildHandler
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Attempted mid-tunnel injection from " + prev 
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
        
        //if (_log.shouldLog(Log.DEBUG)) {
        //    _log.debug("IV received before decrypt: " + Base64.encode(orig, offset, IV_LENGTH));
        //    _log.debug("Data before processing:\n" + Base64.encode(orig, IV_LENGTH, orig.length - IV_LENGTH));
        //}

        SessionKey ivkey = _config.getIVKey();
        AESEngine aes = _context.aes();
        // double IV encryption
        aes.encryptBlock(orig, offset, ivkey, orig, offset);
        aes.encrypt(orig, offset + IV_LENGTH, orig, offset + IV_LENGTH, _config.getLayerKey(),
                    orig, offset, length - IV_LENGTH);
        aes.encryptBlock(orig, offset, ivkey, orig, offset);

        //if (_log.shouldLog(Log.DEBUG)) {
        //    _log.debug("IV sent: " + Base64.encode(orig, offset, IV_LENGTH));
        //    _log.debug("Data after processing:\n" + Base64.encode(orig, IV_LENGTH, orig.length - IV_LENGTH));
        //}
        return true;
    }

    /**
     *  @since 0.8.12
     */
    @Override
    public String toString() {
        return getClass().getSimpleName() + " for " + _config;
    }
}
