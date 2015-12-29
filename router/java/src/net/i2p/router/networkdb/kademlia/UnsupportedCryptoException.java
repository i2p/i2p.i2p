package net.i2p.router.networkdb.kademlia;

/**
 *  Signature verification failed because the
 *  sig type is unknown or unavailable.
 *
 *  @since 0.9.16
 */
public class UnsupportedCryptoException extends IllegalArgumentException {

    public UnsupportedCryptoException(String msg) {
        super(msg);
    }

    public UnsupportedCryptoException(String msg, Throwable t) {
        super(msg, t);
    }
}
