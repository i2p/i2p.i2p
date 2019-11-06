package net.i2p.router.crypto.ratchet;

import net.i2p.crypto.EncType;
import net.i2p.data.DataFormatException;
import net.i2p.data.PrivateKey;
import net.i2p.router.RouterContext;
import net.i2p.router.message.CloveSet;
import net.i2p.util.Log;

/**
 * Handles the actual decryption using the
 * supplied keys and data.
 *
 * @since 0.9.44
 */
final class MuxedEngine {
    private final RouterContext _context;
    private final Log _log;

    public MuxedEngine(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(MuxedEngine.class);
    }

    /**
     * Decrypt the message with the given private keys
     *
     * @param elgKey must be ElG, non-null
     * @param ecKey must be EC, non-null
     * @return decrypted data or null on failure
     */
    public CloveSet decrypt(byte data[], PrivateKey elgKey, PrivateKey ecKey, MuxedSKM keyManager) throws DataFormatException {
        if (elgKey.getType() != EncType.ELGAMAL_2048 ||
            ecKey.getType() != EncType.ECIES_X25519)
            throw new IllegalArgumentException();
        CloveSet rv = null;
        boolean tryElg = false;
        // See proposal 144
        if (data.length >= 128) {
            int mod = data.length % 16;
            if (mod == 0 || mod == 2)
                tryElg = true;
        }
        // Always try ElG first, for now
        if (tryElg) {
            byte[] dec = _context.elGamalAESEngine().decrypt(data, elgKey, keyManager.getElgSKM());
            if (dec != null) {
                try {
                    rv = _context.garlicMessageParser().readCloveSet(dec, 0);
                } catch (DataFormatException dfe) {
                    if (_log.shouldWarn())
                        _log.warn("ElG decrypt failed, trying ECIES", dfe);
                }
            } else {
                if (_log.shouldWarn())
                    _log.warn("ElG decrypt failed, trying ECIES");
            }
        }
        if (rv == null) {
            rv = _context.eciesEngine().decrypt(data, ecKey, keyManager.getECSKM());
        }
        return rv;
    }
}
