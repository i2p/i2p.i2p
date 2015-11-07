package net.i2p.data.i2cp;

/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

/**
 * Request the router look up the dest for a hash
 * or a host. Replaces DestLookupMessage.
 *
 * @since 0.9.11; do not send to routers older than 0.9.11.
 */
public class HostLookupMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 38;

    private long _reqID;
    private long _timeout;
    private int _lookupType;
    private Hash _hash;
    private String _host;
    private SessionId _sessionId;

    public static final int LOOKUP_HASH = 0;
    public static final int LOOKUP_HOST = 1;

    private static final long MAX_INT = (1L << 32) - 1;

    public HostLookupMessage() {}

    /**
     *  @param reqID 0 to 2**32 - 1
     *  @param timeout ms 1 to 2**32 - 1
     */
    public HostLookupMessage(SessionId id, Hash h, long reqID, long timeout) {
        if (id == null || h == null)
            throw new IllegalArgumentException();
        if (reqID < 0 || reqID > MAX_INT)
            throw new IllegalArgumentException();
        if (timeout <= 0 || timeout > MAX_INT)
            throw new IllegalArgumentException();
        _sessionId = id;
        _hash = h;
        _reqID = reqID;
        _timeout = timeout;
        _lookupType = LOOKUP_HASH;
    }

    /**
     *  @param reqID 0 to 2**32 - 1
     *  @param timeout ms 1 to 2**32 - 1
     */
    public HostLookupMessage(SessionId id, String host, long reqID, long timeout) {
        if (id == null || host == null)
            throw new IllegalArgumentException();
        if (reqID < 0 || reqID > MAX_INT)
            throw new IllegalArgumentException();
        if (timeout <= 0 || timeout > MAX_INT)
            throw new IllegalArgumentException();
        _sessionId = id;
        _host = host;
        _reqID = reqID;
        _timeout = timeout;
        _lookupType = LOOKUP_HOST;
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
     *  @return ms 1 to 2**32 - 1
     */
    public long getTimeout() {
        return _timeout;
    }

    /**
     *  @return 0 (hash) or 1 (host)
     */
    public int getLookupType() {
        return _lookupType;
    }

    /**
     *  @return only valid if lookup type == 0
     */
    public Hash getHash() {
        return _hash;
    }

    /**
     *  @return only valid if lookup type == 1
     */
    public String getHostname() {
        return _host;
    }

    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _reqID = DataHelper.readLong(in, 4);
            _timeout = DataHelper.readLong(in, 4);
            _lookupType = (int) DataHelper.readLong(in, 1);
            if (_lookupType == LOOKUP_HASH) {
                _hash = Hash.create(in);
            } else if (_lookupType == LOOKUP_HOST) {
                _host = DataHelper.readString(in);
                if (_host.length() == 0)
                    throw new I2CPMessageException("bad host");
            } else {
                throw new I2CPMessageException("bad type");
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("bad data", dfe);
        }
    }

    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        int len;
        if (_lookupType == LOOKUP_HASH) {
            if (_hash == null)
                throw new I2CPMessageException("Unable to write out the message as there is not enough data");
            len = 11 + Hash.HASH_LENGTH;
        } else if (_lookupType == LOOKUP_HOST) {
            if (_host == null)
                throw new I2CPMessageException("Unable to write out the message as there is not enough data");
            len = 12 + _host.length();
        } else {
            throw new I2CPMessageException("bad type");
        }
        ByteArrayOutputStream os = new ByteArrayOutputStream(len);
        try {
            _sessionId.writeBytes(os);
            DataHelper.writeLong(os, 4, _reqID);
            DataHelper.writeLong(os, 4, _timeout);
            DataHelper.writeLong(os, 1, _lookupType);
            if (_lookupType == LOOKUP_HASH) {
                _hash.writeBytes(os);
            } else {
                DataHelper.writeString(os, _host);
            }
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
        buf.append("[HostLookupMessage: ");
        buf.append("\n\t").append(_sessionId);
        buf.append("\n\tReqID: ").append(_reqID);
        buf.append("\n\tTimeout: ").append(_timeout);
        if (_lookupType == LOOKUP_HASH)
            buf.append("\n\tHash: ").append(_hash);
        else if (_lookupType == LOOKUP_HOST)
            buf.append("\n\tHost: ").append(_host);
        buf.append("]");
        return buf.toString();
    }
}
