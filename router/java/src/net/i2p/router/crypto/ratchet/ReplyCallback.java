package net.i2p.router.crypto.ratchet;

/**
 * ECIES will call this back if an ack was requested and received.
 *
 * @since 0.9.46
 */
public interface ReplyCallback {

    /**
     *  When does this callback expire?
     *  @return java time
     */
    public long getExpiration();

    /**
     *  A reply was received.
     */
    public void onReply();
}
