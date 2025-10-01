package net.i2p.router.crypto.ratchet;

import net.i2p.crypto.EncAlgo;
import net.i2p.crypto.EncType;
import net.i2p.data.DataFormatException;
import net.i2p.data.PrivateKey;
import net.i2p.router.RouterContext;
import net.i2p.router.message.CloveSet;
import net.i2p.util.Log;

/**
 * Both EC and PQ
 *
 * Handles the actual decryption using the
 * supplied keys and data.
 *
 * @since 0.9.67
 */
final class MuxedPQEngine {
    private final RouterContext _context;
    private final Log _log;

    public MuxedPQEngine(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(MuxedPQEngine.class);
    }

    /**
     * Decrypt the message with the given private keys
     *
     * @param ecKey must be EC, non-null
     * @param pqKey must be PQ, non-null
     * @return decrypted data or null on failure
     */
    public CloveSet decrypt(byte data[], PrivateKey ecKey, PrivateKey pqKey, MuxedPQSKM keyManager) throws DataFormatException {
        if (ecKey.getType() != EncType.ECIES_X25519 ||
            pqKey.getType().getBaseAlgorithm() != EncAlgo.ECIES_MLKEM)
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
                _log.debug("Ratchet tag not found before PQ");
        }
        // PQ
        // Ratchet Tag
        rv = _context.eciesEngine().decryptFast(data, pqKey, keyManager.getPQSKM());
        if (rv != null)
            return rv;
        if (debug)
            _log.debug("PQ tag not found");
        if (!preferRatchet) {
            // Ratchet Tag
            rv = _context.eciesEngine().decryptFast(data, ecKey, keyManager.getECSKM());
            if (rv != null)
                return rv;
            if (debug)
                _log.debug("Ratchet tag not found after PQ");
        }

        if (preferRatchet) {
            // Ratchet DH
            rv = _context.eciesEngine().decryptSlow(data, ecKey, keyManager.getECSKM());
            boolean ok = rv != null;
            keyManager.reportDecryptResult(true, ok);
            if (ok)
                return rv;
            if (debug)
                _log.debug("Ratchet NS decrypt failed before PQ");
        }

        // PQ DH
        // Minimum size checks for the larger New Session message are in ECIESAEADEngine.x_decryptSlow().
        rv = _context.eciesEngine().decryptSlow(data, pqKey, keyManager.getPQSKM());
        boolean isok = rv != null;
        keyManager.reportDecryptResult(false, isok);
        if (isok)
            return rv;
        if (debug)
            _log.debug("PQ NS decrypt failed");

        if (!preferRatchet) {
            // Ratchet DH
            rv = _context.eciesEngine().decryptSlow(data, ecKey, keyManager.getECSKM());
            boolean ok = rv != null;
            keyManager.reportDecryptResult(true, ok);
            if (!ok && debug)
                _log.debug("Ratchet NS decrypt failed after PQ");
        }
        return rv;
    }
}
