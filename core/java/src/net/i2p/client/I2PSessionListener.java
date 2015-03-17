package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Define a means for the router to asynchronously notify the client that a
 * new message is available or the router is under attack.
 *
 * A client must implement and register this via addSessionListener()
 * to receive messages.
 *
 * If you wish to get notification of the protocol, from port, and to port,
 * or wish to get notification of certain protocols and ports only,
 * you must use I2PSessionMuxedListener and addMuxedSessionListener() instead.
 *
 * @author jrandom
 */
public interface I2PSessionListener {
    /** Instruct the client that the given session has received a message with
     * size # of bytes.
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

    /** Instruct the client that the session specified seems to be under attack
     * and that the client may wish to move its destination to another router.
     *
     * Unused. Not fully implemented.
     *
     * @param session session to report abuse to
     * @param severity how bad the abuse is
     */
    void reportAbuse(I2PSession session, int severity);

    /**
     * Notify the client that the session has been terminated
     *
     */
    void disconnected(I2PSession session);

    /**
     * Notify the client that some error occurred
     *
     * @param error can be null? or not?
     */
    void errorOccurred(I2PSession session, String message, Throwable error);
}
