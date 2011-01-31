package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

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
 * Unencrypt a garlic message and pass off any valid cloves to the configured
 * receiver to dispatch as they choose.
 *
 */
public class GarlicMessageReceiver {
    private final RouterContext _context;
    private final Log _log;
    private final CloveReceiver _receiver;
    private final Hash _clientDestination;
    private final GarlicMessageParser _parser;
   
    private final static int FORWARD_PRIORITY = 50;
    
    public interface CloveReceiver {
        public void handleClove(DeliveryInstructions instructions, I2NPMessage data);
    }
    
    public GarlicMessageReceiver(RouterContext context, CloveReceiver receiver) {
        this(context, receiver, null);
    }
    public GarlicMessageReceiver(RouterContext context, CloveReceiver receiver, Hash clientDestination) {
        _context = context;
        _log = context.logManager().getLog(GarlicMessageReceiver.class);
        _context.statManager().createRateStat("crypto.garlic.decryptFail", "How often garlic messages are undecryptable", "Encryption", new long[] { 5*60*1000, 60*60*1000, 24*60*60*1000 });
        _clientDestination = clientDestination;
        _parser = new GarlicMessageParser(context);
        _receiver = receiver;
        //_log.error("New GMR dest = " + clientDestination);
    }
    
    public void receive(GarlicMessage message) {
        PrivateKey decryptionKey = null;
        SessionKeyManager skm = null;
        if (_clientDestination != null) {
            LeaseSetKeys keys = _context.keyManager().getKeys(_clientDestination);
            skm = _context.clientManager().getClientSessionKeyManager(_clientDestination);
            if (keys != null && skm != null) {
                decryptionKey = keys.getDecryptionKey();
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Not trying to decrypt a garlic routed message to a disconnected client");
                return;
            }
        } else {
            decryptionKey = _context.keyManager().getPrivateKey();
            skm = _context.sessionKeyManager();
        }
        
        CloveSet set = _parser.getGarlicCloves(message, decryptionKey, skm);
        if (set != null) {
            for (int i = 0; i < set.getCloveCount(); i++) {
                GarlicClove clove = set.getClove(i);
                handleClove(clove);
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("CloveMessageParser failed to decrypt the message [" + message.getUniqueId() 
                           + "]", new Exception("Decrypt garlic failed"));
            _context.statManager().addRateData("crypto.garlic.decryptFail", 1, 0);
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
            if (_log.shouldLog(Log.DEBUG))
                _log.warn("Invalid clove " + clove);
            return;
        } 
        if (_receiver != null)
            _receiver.handleClove(clove.getInstructions(), clove.getData());
    }
    
    private boolean isValid(GarlicClove clove) {
        String invalidReason = _context.messageValidator().validateMessage(clove.getCloveId(), 
                                                                          clove.getExpiration().getTime());
        if (invalidReason != null) {
            String howLongAgo = DataHelper.formatDuration(_context.clock().now()-clove.getExpiration().getTime());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Clove is NOT valid: id=" + clove.getCloveId() 
                           + " expiration " + howLongAgo + " ago", new Exception("Invalid within..."));
            else if (_log.shouldLog(Log.WARN))
                _log.warn("Clove is NOT valid: id=" + clove.getCloveId() 
                           + " expiration " + howLongAgo + " ago: " + invalidReason + ": " + clove);
            _context.messageHistory().messageProcessingError(clove.getCloveId(), 
                                                             clove.getData().getClass().getName(), 
                                                             "Clove is not valid (expiration " + howLongAgo + " ago)");
        }
        return (invalidReason == null);
    }
}
