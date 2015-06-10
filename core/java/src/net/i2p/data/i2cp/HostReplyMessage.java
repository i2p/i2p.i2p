package net.i2p.data.i2cp;

/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;

/**
 * Response to HostLookupMessage. Replaces DestReplyMessage.
 *
 * @since 0.9.11
 */
public class HostReplyMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 39;

    private Destination _dest;
    private long _reqID;
    private int _code;
    private SessionId _sessionId;

    public static final int RESULT_SUCCESS = 0;
    /** generic fail, other codes TBD */
    public static final int RESULT_FAILURE = 1;

    private static final long MAX_INT = (1L << 32) - 1;

    public HostReplyMessage() {}

    /**
     *  A message with RESULT_SUCCESS and a non-null Destination.
     *
     *  @param d non-null
     *  @param reqID 0 to 2**32 - 1
     */
    public HostReplyMessage(SessionId id, Destination d, long reqID) {
        if (id == null || d == null)
            throw new IllegalArgumentException();
        if (reqID < 0 || reqID > MAX_INT)
            throw new IllegalArgumentException();
        _sessionId = id;
        _dest = d;
        _reqID = reqID;
    }

    /**
     *  A message with a failure code and no Destination.
     *
     *  @param failureCode 1-255
     *  @param reqID from the HostLookup 0 to 2**32 - 1
     */
    public HostReplyMessage(SessionId id, int failureCode, long reqID) {
        if (id == null)
            throw new IllegalArgumentException();
        if (failureCode <= 0 || failureCode > 255)
            throw new IllegalArgumentException();
        if (reqID < 0 || reqID > MAX_INT)
            throw new IllegalArgumentException();
        _sessionId = id;
        _code = failureCode;
        _reqID = reqID;
    }

    public SessionId getSessionId() {
        return _sessionId;
    }

    /**
     * Return the SessionId for this message.
     *
     * @since 0.9.21
     */
    @Override
    public SessionId sessionId() {
        return _sessionId;
    }

    /**
     *  @return 0 to 2**32 - 1
     */
    public long getReqID() {
        return _reqID;
    }

    /**
     *  @return 0 on success, 1-255 on failure
     */
    public int getResultCode() {
        return _code;
    }

    /**
     *  @return non-null only if result code is zero
     */
    public Destination getDestination() {
        return _dest;
    }

    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _reqID = DataHelper.readLong(in, 4);
            _code = (int) DataHelper.readLong(in, 1);
            if (_code == RESULT_SUCCESS)
                _dest = Destination.create(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("bad data", dfe);
        }
    }

    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        int len = 7;
        if (_code == RESULT_SUCCESS) {
            if (_dest == null)
                throw new I2CPMessageException("Unable to write out the message as there is not enough data");
            len += _dest.size();
        }
        ByteArrayOutputStream os = new ByteArrayOutputStream(len);
        try {
            _sessionId.writeBytes(os);
            DataHelper.writeLong(os, 4, _reqID);
            DataHelper.writeLong(os, 1, _code);
            if (_code == RESULT_SUCCESS)
                _dest.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("bad data", dfe);
        }
        return os.toByteArray();
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[HostReplyMessage: ");
        buf.append("\n\t").append(_sessionId);
        buf.append("\n\tReqID: ").append(_reqID);
        buf.append("\n\tResult: ").append(_code);
        if (_code == RESULT_SUCCESS)
            buf.append("\n\tDestination: ").append(_dest);
        buf.append("]");
        return buf.toString();
    }
}
