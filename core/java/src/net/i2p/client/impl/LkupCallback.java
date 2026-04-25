package net.i2p.client.impl;

import net.i2p.client.LookupCallback;
import net.i2p.client.LookupResult;

/**
 * The return value of I2PSession.lookupDest2()
 *
 * @since 0.9.70
 */
public class LkupCallback implements LookupCallback {

    private LookupResult _result;

    /**
     *  The callback
     */
    public synchronized void complete(LookupResult result) {
        _result = result;
        this.notifyAll();
    }

    /**
     *  The result or null
     */
    public synchronized LookupResult getResult() {
        return _result;
    }
}
