package net.i2p.client;

/**
 * Deferred callback for IPSession.lookupNonblocking()
 *
 * @since 0.9.67
 */
public interface LookupCallback {

    /**
     *  The result
     */
    public void complete(LookupResult result);
}
