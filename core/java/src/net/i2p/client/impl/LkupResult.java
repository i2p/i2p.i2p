package net.i2p.client.impl;

import java.util.Properties;

import net.i2p.client.LookupResult;
import net.i2p.data.Destination;

/**
 * The return value of I2PSession.lookupDest2()
 *
 * @since 0.9.43
 */
public class LkupResult implements LookupResult {

    private final int _code;
    private final Destination _dest;
    private final int _nonce;
    private final Properties _opts;

    LkupResult(int code, Destination dest) {
        this(code, dest, 0, null);
    }

    /**
     * Deferred
     *
     * @since 0.9.67
     */
    LkupResult(int nonce) {
        this(RESULT_DEFERRED, null, nonce, null);
    }

    /**
     * Async
     *
     * @since 0.9.67
     */
    LkupResult(int code, Destination dest, int nonce) {
        this(code, dest, nonce, null);
    }

    /**
     * See proposal 167
     *
     * @since 0.9.70
     */
    LkupResult(int code, Destination dest, int nonce, Properties opts) {
        _code = code;
        _dest = dest;
        _nonce = nonce;
        _opts = opts;
    }

    /**
     * @return zero for success, nonzero for failure
     */
    public int getResultCode() { return _code; }

    /**
     * @return Destination on success, null on failure
     */
    public Destination getDestination() { return _dest; }

    /**
     * See proposal 167
     * @return options from leaseset or null
     * @since 0.9.70
     */
    public Properties getOptions() { return _opts; }

    /**
     * For async calls only. Nonce will be non-zero.
     * Callback will be called later with the final result and the same nonce.
     *
     * @since 0.9.67
     */
    public int getNonce() { return _nonce; }
}
