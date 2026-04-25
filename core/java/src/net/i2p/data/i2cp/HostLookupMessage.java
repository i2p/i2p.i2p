package net.i2p.data.i2cp;

/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.ByteArrayStream;

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
    private Destination _dest;
    private SessionId _sessionId;

    public static final int LOOKUP_HASH = 0;
    public static final int LOOKUP_HOST = 1;
    public static final int LOOKUP_HASH_OPT = 2;
    public static final int LOOKUP_HOST_OPT = 3;
    public static final int LOOKUP_DEST_OPT = 4;

    private static final long MAX_INT = (1L << 32) - 1;

    public HostLookupMessage() {}

    /**
     *  @param reqID 0 to 2**32 - 1
     *  @param timeout ms 1 to 2**32 - 1
     */
    public HostLookupMessage(SessionId id, Hash h, long reqID, long timeout) {
        this(id, h, reqID, timeout, false);
    }

    /**
     *  @param reqID 0 to 2**32 - 1
     *  @param timeout ms 1 to 2**32 - 1
     *  @param reqOpts true to request LS2 options
     *  @since 0.9.67
     */
    public HostLookupMessage(SessionId id, Hash h, long reqID, long timeout, boolean reqOpts) {
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
        _lookupType = reqOpts ? LOOKUP_HASH_OPT : LOOKUP_HASH;
    }

    /**
     *  @param reqID 0 to 2**32 - 1
     *  @param timeout ms 1 to 2**32 - 1
     */
    public HostLookupMessage(SessionId id, String host, long reqID, long timeout) {
        this(id, host, reqID, timeout, false);
    }

    /**
     *  @param reqID 0 to 2**32 - 1
     *  @param timeout ms 1 to 2**32 - 1
     *  @param reqOpts true to request LS2 options
     *  @since 0.9.67
     */
    public HostLookupMessage(SessionId id, String host, long reqID, long timeout, boolean reqOpts) {
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
        _lookupType = reqOpts ? LOOKUP_HOST_OPT : LOOKUP_HOST;
    }

    /**
     *  Always requests LS2 options
     *
     *  @param reqID 0 to 2**32 - 1
     *  @param timeout ms 1 to 2**32 - 1
     *  @since 0.9.70
     */
    public HostLookupMessage(SessionId id, Destination dest, long reqID, long timeout) {
        if (id == null || dest == null)
            throw new IllegalArgumentException();
        if (reqID < 0 || reqID > MAX_INT)
            throw new IllegalArgumentException();
        if (timeout <= 0 || timeout > MAX_INT)
            throw new IllegalArgumentException();
        _sessionId = id;
        _dest = dest;
        _hash = dest.calculateHash();
        _reqID = reqID;
        _timeout = timeout;
        _lookupType = LOOKUP_DEST_OPT;
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
     *  @return 0-4
     */
    public int getLookupType() {
        return _lookupType;
    }

    /**
     *  @return only valid if lookup type == 0 or 2 or 4
     */
    public Hash getHash() {
        return _hash;
    }

    /**
     *  @return only valid if lookup type == 1 or 3
     */
    public String getHostname() {
        return _host;
    }

    /**
     *  @return only valid if lookup type == 4
     *  @since 0.9.70
     */
    public Destination getDestination() {
        return _dest;
    }

    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _reqID = DataHelper.readLong(in, 4);
            _timeout = DataHelper.readLong(in, 4);
            _lookupType = in.read();
            if (_lookupType < 0)
                throw new EOFException();
            switch (_lookupType) {
              case LOOKUP_HASH:
              case LOOKUP_HASH_OPT:
                _hash = Hash.create(in);
                break;

              case LOOKUP_HOST:
              case LOOKUP_HOST_OPT:
                _host = DataHelper.readString(in);
                if (_host.length() == 0)
                    throw new I2CPMessageException("bad host");
                break;

              case LOOKUP_DEST_OPT:
                _dest = Destination.create(in);
                break;

              default:
                throw new I2CPMessageException("bad type");
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("bad data", dfe);
        }
    }

    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        int len;
        switch (_lookupType) {
          case LOOKUP_HASH:
          case LOOKUP_HASH_OPT:
            if (_hash == null)
                throw new I2CPMessageException("Unable to write out the message as there is not enough data");
            len = 11 + Hash.HASH_LENGTH;
            break;

          case LOOKUP_HOST:
          case LOOKUP_HOST_OPT:
            if (_host == null)
                throw new I2CPMessageException("Unable to write out the message as there is not enough data");
            len = 12 + _host.length();
            break;

          case LOOKUP_DEST_OPT:
            len = 11 + _dest.size();
            break;

          default:
            throw new I2CPMessageException("bad type");
        }
        ByteArrayStream os = new ByteArrayStream(len);
        try {
            _sessionId.writeBytes(os);
            DataHelper.writeLong(os, 4, _reqID);
            DataHelper.writeLong(os, 4, _timeout);
            os.write((byte) _lookupType);
            switch (_lookupType) {
              case LOOKUP_HASH:
              case LOOKUP_HASH_OPT:
                _hash.writeBytes(os);
                break;

              case LOOKUP_HOST:
              case LOOKUP_HOST_OPT:
                DataHelper.writeString(os, _host);
                break;

              case LOOKUP_DEST_OPT:
                _dest.writeBytes(os);
                break;
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
        buf.append("\n\tType: ").append(_lookupType);
        switch (_lookupType) {
          case LOOKUP_HASH:
          case LOOKUP_HASH_OPT:
            buf.append("\n\tHash: ").append(_hash);
            break;

          case LOOKUP_HOST:
          case LOOKUP_HOST_OPT:
            buf.append("\n\tHost: ").append(_host);
            break;

          case LOOKUP_DEST_OPT:
            buf.append("\n\tDest: ").append(_dest);
            break;
        }
        buf.append("]");
        return buf.toString();
    }
}
