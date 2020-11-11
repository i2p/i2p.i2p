package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.crypto.EncType;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Decrypt a garlic message and pass off any valid cloves to the configured
 * receiver to dispatch as they choose.
 *
 */
public class GarlicMessageReceiver {
    private final RouterContext _context;
    private final Log _log;
    private final CloveReceiver _receiver;
    private final Hash _clientDestination;
   
    public interface CloveReceiver {
        public void handleClove(DeliveryInstructions instructions, I2NPMessage data);
    }
    
    /**
     *  @param receiver non-null
     */
    public GarlicMessageReceiver(RouterContext context, CloveReceiver receiver) {
        this(context, receiver, null);
    }

    /**
     *  @param receiver non-null
     */
    public GarlicMessageReceiver(RouterContext context, CloveReceiver receiver, Hash clientDestination) {
        _context = context;
        _log = context.logManager().getLog(GarlicMessageReceiver.class);
        _clientDestination = clientDestination;
        _receiver = receiver;
        //_log.error("New GMR dest = " + clientDestination);
        // all createRateStat in OCMOSJ.init()
    }
    
    public void receive(GarlicMessage message) {
        PrivateKey decryptionKey;
        PrivateKey decryptionKey2 = null;
        SessionKeyManager skm;
        if (_clientDestination != null) {
            LeaseSetKeys keys = _context.keyManager().getKeys(_clientDestination);
            skm = _context.clientManager().getClientSessionKeyManager(_clientDestination);
            if (keys != null && skm != null) {
                decryptionKey = keys.getDecryptionKey();
                decryptionKey2 = keys.getDecryptionKey(EncType.ECIES_X25519);
                if (decryptionKey == null && decryptionKey2 == null) {
                    if (_log.shouldWarn())
                        _log.warn("No key to decrypt for " + _clientDestination.toBase32());
                    return;
                }
                if (decryptionKey == null) {
                    // swap
                    decryptionKey = decryptionKey2;
                    decryptionKey2 = null;
                }
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Not decrypting " + message + " for disconnected " + _clientDestination.toBase32());
                return;
            }
        } else {
            decryptionKey = _context.keyManager().getPrivateKey();
            skm = _context.sessionKeyManager();
        }
        
        // Pass both keys if available for muxed decrypt
        CloveSet set;
        if (decryptionKey2 != null)
            set = _context.garlicMessageParser().getGarlicCloves(message, decryptionKey, decryptionKey2, skm);
        else
            set = _context.garlicMessageParser().getGarlicCloves(message, decryptionKey, skm);
        if (set != null) {
            for (int i = 0; i < set.getCloveCount(); i++) {
                GarlicClove clove = set.getClove(i);
                handleClove(clove);
            }
        } else {
            if (_log.shouldLog(Log.WARN)) {
                String d = (_clientDestination != null) ? _clientDestination.toBase32() : "the router";
                String keys = (decryptionKey2 != null) ? "both ElGamal and ECIES keys" : decryptionKey.getType().toString();
                _log.warn("Failed to decrypt " + message + " for " + d + " with " + keys);
            }
            _context.statManager().addRateData("crypto.garlic.decryptFail", 1);
            _context.messageHistory().messageProcessingError(message.getUniqueId(), 
                                                             message.getClass().getName(), 
                                                             "Garlic could not be decrypted");
        }
    }

    /**
     * Validate and pass off any valid cloves to the receiver
     *
     */
    private void handleClove(GarlicClove clove) {
        if (!isValid(clove)) {
            //if (_log.shouldLog(Log.WARN))
            //    _log.warn("Invalid clove " + clove);
            return;
        } 
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("valid clove " + clove);
        _receiver.handleClove(clove.getInstructions(), clove.getData());
    }
    
    private boolean isValid(GarlicClove clove) {
        // As of 0.9.44, no longer check the clove ID in the Bloom filter, just check the expiration.
        // The Clove ID is just another random number, and the message ID in the clove
        // will be checked in the Bloom filter; that is sufficient.
        // Checking the clove ID as well just doubles the number of entries in the Bloom filter,
        // doubling the number of false positives over what is expected.
        // For ECIES-Ratchet, the clove ID is set to the message ID after decryption, as there
        // is no longer a separate field for the clove ID in the transmission format.
        //String invalidReason = _context.messageValidator().validateMessage(clove.getCloveId(), 
        //                                                                   clove.getExpiration().getTime());
        String invalidReason = _context.messageValidator().validateMessage(clove.getExpiration());

        boolean rv = invalidReason == null;
        if (!rv) {
            String howLongAgo = DataHelper.formatDuration(_context.clock().now()-clove.getExpiration());
            if (_log.shouldInfo())
                _log.info("Clove is NOT valid: id=" + clove.getCloveId() 
                           + " expiration " + howLongAgo + " ago", new Exception("Invalid within..."));
            else if (_log.shouldWarn())
                _log.warn("Clove is NOT valid: id=" + clove.getCloveId() 
                           + " expiration " + howLongAgo + " ago: " + invalidReason + ": " + clove);
            _context.messageHistory().messageProcessingError(clove.getCloveId(), 
                                                             clove.getData().getClass().getSimpleName(), 
                                                             "Clove is not valid (expiration " + howLongAgo + " ago)");
        }
        return rv;
    }
}
