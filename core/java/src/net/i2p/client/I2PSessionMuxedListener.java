package net.i2p.client;

/*
 * public domain
 */

/**
 * Define a means for the router to asynchronously notify the client that a
 * new message is available or the router is under attack.
 *
 * @author zzz extends I2PSessionListener
 */
public interface I2PSessionMuxedListener extends I2PSessionListener {

    /**
     * Will be called only if you register via
     * setSessionListener() or addSessionListener().
     * And if you are doing that, just use I2PSessionListener.
     *
     * If you register via addSessionListener(),
     * this will be called only for the proto(s) and toport(s) you register for.
     *
     * After this is called, the client should call receiveMessage(msgId).
     * There is currently no method for the client to reject the message.
     * If the client does not call receiveMessage() within a timeout period
     * (currently 30 seconds), the session will delete the message and
     * log an error.
     *
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message - why it's a long and not an int is a mystery
     */
    void messageAvailable(I2PSession session, int msgId, long size);

    /**
     * Instruct the client that the given session has received a message
     *
     * Will be called only if you register via addMuxedSessionListener().
     * Will be called only for the proto(s) and toport(s) you register for.
     *
     * After this is called, the client should call receiveMessage(msgId).
     * There is currently no method for the client to reject the message.
     * If the client does not call receiveMessage() within a timeout period
     * (currently 30 seconds), the session will delete the message and
     * log an error.
     *
     * Only one listener is called for a given message, even if more than one
     * have registered. See I2PSessionDemultiplexer for details.
     *
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message - why it's a long and not an int is a mystery
     * @param proto 1-254 or 0 for unspecified
     * @param fromport 1-65535 or 0 for unspecified
     * @param toport 1-65535 or 0 for unspecified
     */
    void messageAvailable(I2PSession session, int msgId, long size, int proto, int fromport, int toport);

    /** Instruct the client that the session specified seems to be under attack
     * and that the client may wish to move its destination to another router.
     * All registered listeners will be called.
     *
     * Unused. Not fully implemented.
     *
     * @param session session to report abuse to
     * @param severity how bad the abuse is
     */
    void reportAbuse(I2PSession session, int severity);

    /**
     * Notify the client that the session has been terminated.
     * All registered listeners will be called.
     *
     */
    void disconnected(I2PSession session);

    /**
     * Notify the client that some error occurred.
     * All registered listeners will be called.
     *
     * @param error can be null? or not?
     */
    void errorOccurred(I2PSession session, String message, Throwable error);
}
