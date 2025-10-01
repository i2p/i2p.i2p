package net.i2p.client.impl;

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

    LkupResult(int code, Destination dest) {
        this(code, dest, 0);
    }

    /**
     * Deferred
     *
     * @since 0.9.67
     */
    LkupResult(int nonce) {
        this(RESULT_DEFERRED, null, nonce);
    }

    /**
     * Async
     *
     * @since 0.9.67
     */
    LkupResult(int code, Destination dest, int nonce) {
        _code = code;
        _dest = dest;
        _nonce = nonce;
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
     * For async calls only. Nonce will be non-zero.
     * Callback will be called later with the final result and the same nonce.
     *
     * @since 0.9.67
     */
    public int getNonce() { return _nonce; }
}
