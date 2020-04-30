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
        final boolean debug = _log.shouldDebug();
        CloveSet rv = null;
        // Try in-order from fastest to slowest
        boolean preferRatchet = keyManager.preferRatchet();
        if (preferRatchet) {
            // Ratchet Tag
            rv = _context.eciesEngine().decryptFast(data, ecKey, keyManager.getECSKM());
            if (rv != null)
                return rv;
            if (debug)
                _log.debug("Ratchet tag not found before AES");
        }
        // AES Tag
        if (data.length >= 128 && (data.length & 0x0f) == 0) {
            byte[] dec = _context.elGamalAESEngine().decryptFast(data, elgKey, keyManager.getElgSKM());
            if (dec != null) {
                try {
                    rv = _context.garlicMessageParser().readCloveSet(dec, 0);
                    if (rv == null && _log.shouldInfo())
                        _log.info("AES cloveset error after AES? " + preferRatchet);
                } catch (DataFormatException dfe) {
                    if (_log.shouldInfo())
                        _log.info("AES cloveset error after AES? " + preferRatchet, dfe);
                }
                return rv;
            } else {
                if (debug)
                    _log.debug("AES tag not found after ratchet? " + preferRatchet);
            }
        }
        if (!preferRatchet) {
            // Ratchet Tag
            rv = _context.eciesEngine().decryptFast(data, ecKey, keyManager.getECSKM());
            if (rv != null)
                return rv;
            if (debug)
                _log.debug("Ratchet tag not found after AES");
        }

        if (preferRatchet) {
            // Ratchet DH
            rv = _context.eciesEngine().decryptSlow(data, ecKey, keyManager.getECSKM());
            boolean ok = rv != null;
            keyManager.reportDecryptResult(true, ok);
            if (ok)
                return rv;
            if (debug)
                _log.debug("Ratchet NS decrypt failed before ElG");
        }
        // ElG DH
        if (data.length >= 514 && (data.length & 0x0f) == 2) {
            byte[] dec = _context.elGamalAESEngine().decryptSlow(data, elgKey, keyManager.getElgSKM());
            if (dec != null) {
                try {
                    rv = _context.garlicMessageParser().readCloveSet(dec, 0);
                    boolean ok = rv != null;
                    keyManager.reportDecryptResult(false, ok);
                    if (ok)
                        return rv;
                    if (_log.shouldInfo())
                        _log.info("ElG cloveset error after ratchet? " + preferRatchet);
                } catch (DataFormatException dfe) {
                    if (_log.shouldInfo())
                        _log.info("ElG cloveset error afterRatchet? " + preferRatchet, dfe);
                }
            } else {
                if (_log.shouldInfo())
                    _log.info("ElG decrypt failed after Ratchet? " + preferRatchet);
            }
            keyManager.reportDecryptResult(false, false);
        }
        if (!preferRatchet) {
            // Ratchet DH
            rv = _context.eciesEngine().decryptSlow(data, ecKey, keyManager.getECSKM());
            boolean ok = rv != null;
            keyManager.reportDecryptResult(true, ok);
            if (!ok && debug)
                _log.debug("Ratchet NS decrypt failed after ElG");
        }
        return rv;
    }
}
