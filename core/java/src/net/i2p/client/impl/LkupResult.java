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

    LkupResult(int code, Destination dest) {
        _code = code;
        _dest = dest;
    }

    /**
     * @return zero for success, nonzero for failure
     */
    public int getResultCode() { return _code; }

    /**
     * @return Destination on success, null on failure
     */
    public Destination getDestination() { return _dest; }

}
