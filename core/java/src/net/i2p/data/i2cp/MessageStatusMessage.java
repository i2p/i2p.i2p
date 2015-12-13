package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;

/**
 * Defines the message a router sends to a client about a single message.
 * For incoming messages, it tells the client that a new message is available.
 * For outgoing messages, it tells the client whether the message was delivered.
 *
 * @author jrandom
 */
public class MessageStatusMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 22;
    private int _sessionId;
    private long _messageId;
    private long _nonce;
    private long _size;
    private int _status;

    /**
     *  For incoming messages. All the rest are for outgoing.
     */
    public final static int STATUS_AVAILABLE = 0;
    public final static int STATUS_SEND_ACCEPTED = 1;

    /** unused */
    public final static int STATUS_SEND_BEST_EFFORT_SUCCESS = 2;

    /**
     *  A probable failure, but we don't know for sure.
     */
    public final static int STATUS_SEND_BEST_EFFORT_FAILURE = 3;

    /**
     *  Generic success.
     *  May not really be guaranteed, as the best-effort
     *  success code is unused.
     */
    public final static int STATUS_SEND_GUARANTEED_SUCCESS = 4;

    /**
     *  Generic failure, specific cause unknown.
     *  May not really be a guaranteed failure, as the best-effort
     *  failure code is unused.
     */
    public final static int STATUS_SEND_GUARANTEED_FAILURE = 5;

    /**
     *  The far-end destination is local and we are pretty darn sure
     *  the delivery succeeded.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_SUCCESS_LOCAL = 6;

    /**
     *  The far-end destination is local but delivery failed for some reason.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_LOCAL = 7;

    /**
     *  The router is not ready, has shut down, or has major problems.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_ROUTER = 8;

    /**
     *  The PC apparently has no network connectivity at all.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_NETWORK = 9;

    /**
     *  The session is invalid or closed.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_BAD_SESSION = 10;

    /**
     *  The message payload is invalid or zero-length or too big.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_BAD_MESSAGE = 11;

    /**
     *  Something is invalid in the message options, or the expiration
     *  is too far in the future.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_BAD_OPTIONS = 12;

    /**
     *  Some queue or buffer in the router is full and the message was dropped.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_OVERFLOW = 13;

    /**
     *  Message expired before it could be sent.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_EXPIRED = 14;

    /**
     *  Local leaseset problems. The client has not yet signed
     *  a leaseset, or the local keys are invalid, or it has expired,
     *  or it does not have any tunnels in it.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_LOCAL_LEASESET = 15;

    /**
     *  Local problems - no outbound tunnel to send through,
     *  or no inbound tunnel if a reply is required.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_NO_TUNNELS = 16;

    /**
     *  The certs or options in the destination or leaseset indicate that
     *  it uses an encryption format that we don't support, so we can't talk to it.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_UNSUPPORTED_ENCRYPTION = 17;

    /**
     *  Something strange is wrong with the far-end destination.
     *  Bad format, unsupported options, certificates, etc.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_DESTINATION = 18;

    /**
     *  We got the far-end leaseset but something strange is wrong with it.
     *  Unsupported options or certificates, no tunnels, etc.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_BAD_LEASESET = 19;

    /**
     *  We got the far-end leaseset but it's expired and can't get a new one.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_EXPIRED_LEASESET = 20;

    /**
     *  Could not find the far-end destination's lease set.
     *  This is a common failure, equivalent to a DNS lookup fail.
     *  This is a guaranteed failure.
     *  @since 0.9.5
     */
    public final static int STATUS_SEND_FAILURE_NO_LEASESET = 21;



    public MessageStatusMessage() {
        _sessionId = -1;
        _status = -1;
        _messageId = -1;
        _size = -1;
        _nonce = -1;
    }

    public long getSessionId() {
        return _sessionId;
    }

    /**
     * Return the SessionId for this message.
     *
     * @since 0.9.21
     */
    @Override
    public SessionId sessionId() {
        return _sessionId >= 0 ? new SessionId(_sessionId) : null;
    }

    /** @param id 0-65535 */
    public void setSessionId(long id) {
        _sessionId = (int) id;
    }

    public int getStatus() {
        return _status;
    }

    /** @param status 0-255 */
    public void setStatus(int status) {
        _status = status;
    }

    /**
     *  Is the status code a success status code?
     *  @since 0.9.5
     */
    public boolean isSuccessful() {
        return isSuccessful(_status);
    }

    /**
     *  Is the status code a success status code?
     *  @since 0.9.5
     */
    public static boolean isSuccessful(int status) {
        return status == STATUS_SEND_GUARANTEED_SUCCESS ||
               status == STATUS_SEND_BEST_EFFORT_SUCCESS ||
               status == STATUS_SEND_SUCCESS_LOCAL ||
               status == STATUS_SEND_ACCEPTED ||
               status == STATUS_AVAILABLE;
    }

    /**
     *  This is the router's ID for the message
     */
    public long getMessageId() {
        return _messageId;
    }

    /**
     *  This is the router's ID for the message
     */
    public void setMessageId(long id) {
        _messageId = id;
    }

    public long getSize() {
        return _size;
    }

    public void setSize(long size) {
        _size = size;
    }

    /**
     *  This is the client's ID for the message
     */
    public long getNonce() {
        return _nonce;
    }

    /**
     *  This is the client's ID for the message
     */
    public void setNonce(long nonce) {
        _nonce = nonce;
    }

    public static final String getStatusString(int status) {
        switch (status) {
        case STATUS_AVAILABLE:
            return "AVAILABLE          ";
        case STATUS_SEND_ACCEPTED:
            return "SEND ACCEPTED      ";
        case STATUS_SEND_BEST_EFFORT_SUCCESS:
            return "BEST EFFORT SUCCESS";
        case STATUS_SEND_GUARANTEED_SUCCESS:
            return "GUARANTEED SUCCESS ";
        case STATUS_SEND_SUCCESS_LOCAL:
            return "LOCAL SUCCESS      ";
        case STATUS_SEND_BEST_EFFORT_FAILURE:
            return "PROBABLE FAILURE   ";
        case STATUS_SEND_FAILURE_NO_TUNNELS:
            return "NO LOCAL TUNNELS   ";
        case STATUS_SEND_FAILURE_NO_LEASESET:
            return "LEASESET NOT FOUND ";
        default:
            return "SEND FAILURE CODE: " + status;
        }
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = (int) DataHelper.readLong(in, 2);
            _messageId = DataHelper.readLong(in, 4);
            _status = (int) DataHelper.readLong(in, 1);
            _size = DataHelper.readLong(in, 4);
            _nonce = DataHelper.readLong(in, 4);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    
    /** 
     * Override to reduce mem churn
     * @throws IOException 
     */
    @Override
    public void writeMessage(OutputStream out) throws I2CPMessageException, IOException {
        int len = 2 + // sessionId
                  4 + // messageId
                  1 + // status
                  4 + // size
                  4; // nonce
        
        try {
            DataHelper.writeLong(out, 4, len);
            out.write((byte) MESSAGE_TYPE);
            DataHelper.writeLong(out, 2, _sessionId);
            DataHelper.writeLong(out, 4, _messageId);
            out.write((byte) _status);
            DataHelper.writeLong(out, 4, _size);
            DataHelper.writeLong(out, 4, _nonce);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to write the message length or type", dfe);
        }
    }
    
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        throw new UnsupportedOperationException("This shouldn't be called... use writeMessage(out)");
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[MessageStatusMessage: ");
        buf.append("\n\tSessionId: ").append(_sessionId);
        buf.append("\n\tNonce: ").append(_nonce);
        buf.append("\n\tMessageId: ").append(_messageId);
        buf.append("\n\tStatus: ").append(getStatusString(_status));
        buf.append("\n\tSize: ").append(_size);
        buf.append("]");
        return buf.toString();
    }
}
