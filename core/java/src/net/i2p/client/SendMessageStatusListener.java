package net.i2p.client;

/**
 * Asynchronously notify the client of the status
 * of a sent message.
 *
 * @since 0.9.14
 */
public interface SendMessageStatusListener {

    /** I2CP status codes are 0 - 255. Start our fake ones at 256. */
    public static final int STATUS_CANCELLED = 256;

    /**
     * Tell the client of an update in the send status for a message
     * previously sent with I2PSession.sendMessage().
     * Multiple calls for a single message ID are possible.
     *
     * @param session session notifying
     * @param msgId message number returned from a previous sendMessage() call
     * @param status of the message, as defined in MessageStatusMessage and this class.
     */
    void messageStatus(I2PSession session, long msgId, int status);

}
