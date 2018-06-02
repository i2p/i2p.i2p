package net.i2p.data.i2np;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.data.Hash;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 * Defines the base message implementation.
 *
 * @author jrandom
 */
public abstract class I2NPMessageImpl extends DataStructureImpl implements I2NPMessage {
    protected final Log _log;
    protected final I2PAppContext _context;
    protected long _expiration;

    /**
     *  Warning, lazily initialized by readBytes(), writeBytes(), toByteArray(),
     *  getUniqueId(), and setUniqueId(); otherwise will be -1.
     *  Extending classes should take care when accessing this field;
     *  to ensure initialization, use getUniqueId() instead.
     */
    protected long _uniqueId = -1;

    public final static long DEFAULT_EXPIRATION_MS = 1*60*1000; // 1 minute by default
    public final static int CHECKSUM_LENGTH = 1; //Hash.HASH_LENGTH;

    /** 16 */
    public static final int HEADER_LENGTH = 1 // type
                        + 4 // uniqueId
                        + DataHelper.DATE_LENGTH // expiration
                        + 2 // payload length
                        + CHECKSUM_LENGTH;

    // Whether SSU used the full header or a truncated header.
    // We are stuck with the short header, can't change it now.
    //private static final boolean RAW_FULL_SIZE = false;

    /** unused */
    private static final Map<Integer, Builder> _builders = new ConcurrentHashMap<Integer, Builder>(1);

    /** @deprecated unused */
    @Deprecated
    public static final void registerBuilder(Builder builder, int type) { _builders.put(Integer.valueOf(type), builder); }

    /** interface for extending the types of messages handled - unused */
    public interface Builder {
        /** instantiate a new I2NPMessage to be populated shortly */
        public I2NPMessage build(I2PAppContext ctx);
    }

    public I2NPMessageImpl(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(I2NPMessageImpl.class);
        _expiration = _context.clock().now() + DEFAULT_EXPIRATION_MS;
        //_context.statManager().createRateStat("i2np.writeTime", "How long it takes to write an I2NP message", "I2NP", new long[] { 10*60*1000, 60*60*1000 });
        //_context.statManager().createRateStat("i2np.readTime", "How long it takes to read an I2NP message", "I2NP", new long[] { 10*60*1000, 60*60*1000 });
    }

    /**
     *  Read the whole message but only if it's exactly 1024 bytes.
     *  Unused - All transports provide encapsulation and so we have byte arrays available.
     *
     *  @deprecated unused
     */
    @Deprecated
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        try {
            readBytes(in, -1, new byte[1024]);
        } catch (I2NPMessageException ime) {
            throw new DataFormatException("Bad bytes", ime);
        }
    }

    /**
     *  Read the header, then read the rest into buffer, then call
     *  readMessage in the implemented message type
     *
     *  This does a copy from the stream to the buffer, so if you already
     *  have a byte array, use the other readBytes() instead.
     *
     *<pre>
     *  Specifically:
     *    1 byte type (if caller didn't read already, as specified by the type param
     *    4 byte ID
     *    8 byte expiration
     *    2 byte size
     *    1 byte checksum
     *    size bytes of payload (read by readMessage() in implementation)
     *</pre>
     *
     *  Unused - All transports provide encapsulation and so we have byte arrays available.
     *
     *  @param type the message type or -1 if we should read it here
     *  @param buffer temp buffer to use
     *  @return total length of the message
     *  @deprecated unused
     */
    @Deprecated
    public int readBytes(InputStream in, int type, byte buffer[]) throws I2NPMessageException, IOException {
        try {
            if (type < 0)
                type = (int)DataHelper.readLong(in, 1);
            _uniqueId = DataHelper.readLong(in, 4);
            _expiration = DataHelper.readLong(in, DataHelper.DATE_LENGTH);
            int size = (int)DataHelper.readLong(in, 2);
            byte checksum[] = new byte[CHECKSUM_LENGTH];
            int read = DataHelper.read(in, checksum);
            if (read != CHECKSUM_LENGTH)
                throw new I2NPMessageException("checksum is too small [" + read + "]");
            //Hash h = new Hash();
            //h.readBytes(in);
            if (buffer.length < size) {
                if (size > MAX_SIZE) throw new I2NPMessageException("size=" + size);
                buffer = new byte[size];
            }

            int cur = 0;
            while (cur < size) {
                int numRead = in.read(buffer, cur, size- cur);
                if (numRead == -1) {
                    throw new I2NPMessageException("Payload is too short [" + numRead + ", wanted " + size + "]");
                }
                cur += numRead;
            }

            byte[] calc = SimpleByteCache.acquire(Hash.HASH_LENGTH);
            _context.sha().calculateHash(buffer, 0, size, calc, 0);
            //boolean eq = calc.equals(h);
            boolean eq = DataHelper.eq(checksum, 0, calc, 0, CHECKSUM_LENGTH);
            SimpleByteCache.release(calc);
            if (!eq)
                throw new I2NPMessageException("Bad checksum on " + size + " byte I2NP " + getClass().getSimpleName());

            //long start = _context.clock().now();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Reading bytes: type = " + type + " / uniqueId : " + _uniqueId + " / expiration : " + _expiration);
            readMessage(buffer, 0, size, type);
            //long time = _context.clock().now() - start;
            //if (time > 50)
            //    _context.statManager().addRateData("i2np.readTime", time, time);
            return CHECKSUM_LENGTH + 1 + 2 + 4 + DataHelper.DATE_LENGTH + size;
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error reading the message header", dfe);
        }
    }

    /**
     *  Read the header, then read the rest into buffer, then call
     *  readMessage in the implemented message type
     *
     *<pre>
     *  Specifically:
     *    1 byte type (if caller didn't read already, as specified by the type param
     *    4 byte ID
     *    8 byte expiration
     *    2 byte size
     *    1 byte checksum
     *    size bytes of payload (read by readMessage() in implementation)
     *</pre>
     *
     *  @param type the message type or -1 if we should read it here
     *  @return total length of the message
     */
    public int readBytes(byte data[], int type, int offset) throws I2NPMessageException {
        return readBytes(data, type, offset, data.length - offset);
    }

    /**
     *  Set a limit on the max to read from the data buffer, so that
     *  we can use a large buffer but prevent the reader from reading off the end.
     *
     *  @param maxLen read no more than this many bytes from data starting at offset, even if it is longer
     *                This includes the type byte only if type &lt; 0
     *  @since 0.8.12
     */
    public int readBytes(byte data[], int type, int offset, int maxLen) throws I2NPMessageException {
        int headerSize = HEADER_LENGTH;
        if (type >= 0)
            headerSize--;
        if (maxLen < headerSize)
            throw new I2NPMessageException("Payload is too short " + maxLen);
        int cur = offset;
        if (type < 0) {
            type = data[cur] & 0xff;
            cur++;
        }
        _uniqueId = DataHelper.fromLong(data, cur, 4);
        cur += 4;
        _expiration = DataHelper.fromLong(data, cur, DataHelper.DATE_LENGTH);
        cur += DataHelper.DATE_LENGTH;
        int size = (int)DataHelper.fromLong(data, cur, 2);
        cur += 2;

        if (cur + size > data.length || headerSize + size > maxLen)
            throw new I2NPMessageException("Payload is too short ["
                                           + "data.len=" + data.length
                                           + "maxLen=" + maxLen
                                           + " offset=" + offset
                                           + " cur=" + cur
                                           + " wanted=" + size + "]: " + getClass().getSimpleName());

        int sz = Math.min(size, maxLen - headerSize);
        byte[] calc = SimpleByteCache.acquire(Hash.HASH_LENGTH);
        
        // Compare the checksum in data to the checksum of the data after the checksum
        _context.sha().calculateHash(data, cur + CHECKSUM_LENGTH, sz, calc, 0);
        boolean eq = DataHelper.eq(data, cur, calc, 0, CHECKSUM_LENGTH);
        cur += CHECKSUM_LENGTH;

        SimpleByteCache.release(calc);
        if (!eq)
            throw new I2NPMessageException("Bad checksum on " + size + " byte I2NP " + getClass().getSimpleName());

        //long start = _context.clock().now();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Reading bytes: type = " + type + " / uniqueId : " + _uniqueId + " / expiration : " + _expiration);
        readMessage(data, cur, sz, type);
        cur += sz;
        //long time = _context.clock().now() - start;
        //if (time > 50)
        //    _context.statManager().addRateData("i2np.readTime", time, time);
        return cur - offset;
    }

    /**
     *  Don't do this if you need a byte array - use toByteArray()
     *
     *  @deprecated unused
     */
    @Deprecated
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        int size = getMessageSize();
        if (size < 15 + CHECKSUM_LENGTH) throw new DataFormatException("Unable to build the message");
        byte buf[] = new byte[size];
        int read = toByteArray(buf);
        if (read < 0) throw new DataFormatException("Unable to build the message");
        out.write(buf, 0, read);
    }

    /**
     * Replay resistant message Id
     */
    public long getUniqueId() {
        // Lazy initialization of value
        if (_uniqueId < 0) {
            _uniqueId = _context.random().nextLong(MAX_ID_VALUE);
        }
        return _uniqueId;
    }

    /**
     *  The ID is set to a random value when written but it can be overridden here.
     */
    public void setUniqueId(long id) { _uniqueId = id; }

    /**
     * Date after which the message should be dropped (and the associated uniqueId forgotten)
     *
     */
    public long getMessageExpiration() { return _expiration; }

    /**
     *  The expiration is set to one minute from now in the constructor but it can be overridden here.
     */
    public void setMessageExpiration(long exp) { _expiration = exp; }

    public synchronized int getMessageSize() {
        return calculateWrittenLength() + (15 + CHECKSUM_LENGTH); // 16 bytes in the header
    }

    /**
     *  The raw header consists of a one-byte type and a 4-byte expiration in seconds only.
     *  Used by SSU only!
     */
    public synchronized int getRawMessageSize() {
        //if (RAW_FULL_SIZE)
        //    return getMessageSize();
        //else
            return calculateWrittenLength()+5;
    }

    @Override
    public byte[] toByteArray() {
        byte data[] = new byte[getMessageSize()];
        int written = toByteArray(data);
        if (written != data.length) {
            _log.log(Log.CRIT, "Error writing out " + data.length + " (written: " + written + ", msgSize: " + getMessageSize() +
                               ", writtenLen: " + calculateWrittenLength() + ") for " + getClass().getSimpleName());
            return null;
        }
        return data;
    }

    public int toByteArray(byte buffer[]) {
        try {
            int writtenLen = writeMessageBody(buffer, HEADER_LENGTH);
            int payloadLen = writtenLen - HEADER_LENGTH;
            byte[] h = SimpleByteCache.acquire(Hash.HASH_LENGTH);
            _context.sha().calculateHash(buffer, HEADER_LENGTH, payloadLen, h, 0);

            int off = 0;
            DataHelper.toLong(buffer, off, 1, getType());
            off += 1;

            // Lazy initialization of value
            if (_uniqueId < 0) {
                _uniqueId = _context.random().nextLong(MAX_ID_VALUE);
            }
            DataHelper.toLong(buffer, off, 4, _uniqueId);

            off += 4;
            DataHelper.toLong(buffer, off, DataHelper.DATE_LENGTH, _expiration);
            off += DataHelper.DATE_LENGTH;
            DataHelper.toLong(buffer, off, 2, payloadLen);
            off += 2;
            System.arraycopy(h, 0, buffer, off, CHECKSUM_LENGTH);
            SimpleByteCache.release(h);

            return writtenLen;
        } catch (I2NPMessageException ime) {
            _context.logManager().getLog(getClass()).log(Log.CRIT, "Error writing", ime);
            throw new IllegalStateException("Unable to serialize the message " + getClass().getSimpleName(), ime);
        }
    }

    /** calculate the message body's length (not including the header and footer */
    protected abstract int calculateWrittenLength();

    /**
     * write the message body to the output array, starting at the given index.
     * @return the index into the array after the last byte written
     */
    protected abstract int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException;

    /*
    protected int toByteArray(byte out[], byte[][] prefix, byte[][] suffix) throws I2NPMessageException {
        int curIndex = 0;
        for (int i = 0; i < prefix.length; i++) {
            System.arraycopy(prefix[i], 0, out, curIndex, prefix[i].length);
            curIndex += prefix[i].length;
        }

        curIndex = writeMessageBody(out, curIndex);

        for (int i = 0; i < suffix.length; i++) {
            System.arraycopy(suffix[i], 0, out, curIndex, suffix[i].length);
            curIndex += suffix[i].length;
        }

        return curIndex;
    }
     */


    /**
     *  Write the message with a short 5-byte header.
     *  THe header consists of a one-byte type and a 4-byte expiration in seconds only.
     *  Used by SSU only!
     */
    public int toRawByteArray(byte buffer[]) {
        //if (RAW_FULL_SIZE)
        //    return toByteArray(buffer);
        try {
            int off = 0;
            DataHelper.toLong(buffer, off, 1, getType());
            off += 1;
            // January 19 2038? No, unsigned, good until Feb. 7 2106
            // in seconds, round up so we don't lose time every hop
            DataHelper.toLong(buffer, off, 4, (_expiration + 500) / 1000);
            off += 4;
            return writeMessageBody(buffer, off);
        } catch (I2NPMessageException ime) {
            _context.logManager().getLog(getClass()).log(Log.CRIT, "Error writing", ime);
            throw new IllegalStateException("Unable to serialize the message " + getClass().getSimpleName(), ime);
        }
    }

    public void readMessage(byte data[], int offset, int dataSize, int type, I2NPMessageHandler handler) throws I2NPMessageException {
        // ignore the handler (overridden in subclasses if necessary
        try {
            readMessage(data, offset, dataSize, type);
        } catch (IllegalArgumentException iae) {
            throw new I2NPMessageException("Error reading the message", iae);
        }
    }


/*****
    public static I2NPMessage fromRawByteArray(I2PAppContext ctx, byte buffer[], int offset, int len) throws I2NPMessageException {
        return fromRawByteArray(ctx, buffer, offset, len, new I2NPMessageHandler(ctx));
    }
*****/

    /**
     *  Read the message with a short 5-byte header.
     *  THe header consists of a one-byte type and a 4-byte expiration in seconds only.
     *  Used by SSU only!
     */
    public static I2NPMessage fromRawByteArray(I2PAppContext ctx, byte buffer[], int offset,
                                               int len, I2NPMessageHandler handler) throws I2NPMessageException {
        int type = buffer[offset] & 0xff;
        offset++;
        I2NPMessage msg = createMessage(ctx, type);
        if (msg == null)
            throw new I2NPMessageException("Unknown message type: " + type);
        //if (RAW_FULL_SIZE) {
        //    try {
        //        msg.readBytes(buffer, type, offset);
        //    } catch (IOException ioe) {
        //        throw new I2NPMessageException("Error reading the " + msg, ioe);
        //    }
        //    return msg;
        //}

        try {
            // January 19 2038? No, unsigned, good until Feb. 7 2106
            // in seconds, round up so we don't lose time every hop
            long expiration = (DataHelper.fromLong(buffer, offset, 4) * 1000) + 500;
            offset += 4;
            int dataSize = len - 1 - 4;
            msg.readMessage(buffer, offset, dataSize, type, handler);
            msg.setMessageExpiration(expiration);
            return msg;
        } catch (IllegalArgumentException iae) {
            throw new I2NPMessageException("Corrupt message (negative expiration)", iae);
        }
    }

    /**
     *  Read the message with a short 9-byte header.
     *  THe header consists of a one-byte type, 4-byte ID, and a 4-byte expiration in seconds only.
     *  Used by NTCP2 only!
     *  @since 0.9.35
     */
    public static I2NPMessage fromRawByteArrayNTCP2(I2PAppContext ctx, byte buffer[], int offset,
                                                    int len, I2NPMessageHandler handler) throws I2NPMessageException {
        int type = buffer[offset] & 0xff;
        offset++;
        I2NPMessage msg = createMessage(ctx, type);
        if (msg == null)
            throw new I2NPMessageException("Unknown message type: " + type);

        try {
            long id = DataHelper.fromLong(buffer, offset, 4);
            offset += 4;
            // January 19 2038? No, unsigned, good until Feb. 7 2106
            // in seconds, round up so we don't lose time every hop
            long expiration = (DataHelper.fromLong(buffer, offset, 4) * 1000) + 500;
            offset += 4;
            int dataSize = len - 9;
            msg.readMessage(buffer, offset, dataSize, type, handler);
            msg.setUniqueId(id);
            msg.setMessageExpiration(expiration);
            return msg;
        } catch (IllegalArgumentException iae) {
            throw new I2NPMessageException("Corrupt message (negative expiration)", iae);
        }
    }

    /**
     * Yes, this is fairly ugly, but its the only place it ever happens.
     *
     * @return non-null, returns an UnknownI2NPMessage if unknown type
     */
    public static I2NPMessage createMessage(I2PAppContext context, int type) throws I2NPMessageException {
        switch (type) {
            case DatabaseStoreMessage.MESSAGE_TYPE:
                return new DatabaseStoreMessage(context);
            case DatabaseLookupMessage.MESSAGE_TYPE:
                return new DatabaseLookupMessage(context);
            case DatabaseSearchReplyMessage.MESSAGE_TYPE:
                return new DatabaseSearchReplyMessage(context);
            case DeliveryStatusMessage.MESSAGE_TYPE:
                return new DeliveryStatusMessage(context);
            // unused since forever (0.5?)
            //case DateMessage.MESSAGE_TYPE:
            //    return new DateMessage(context);
            case GarlicMessage.MESSAGE_TYPE:
                return new GarlicMessage(context);
            case TunnelDataMessage.MESSAGE_TYPE:
                return new TunnelDataMessage(context);
            case TunnelGatewayMessage.MESSAGE_TYPE:
                return new TunnelGatewayMessage(context);
            case DataMessage.MESSAGE_TYPE:
                return new DataMessage(context);
            // unused since 0.6.1.10
            case TunnelBuildMessage.MESSAGE_TYPE:
                return new TunnelBuildMessage(context);
            case TunnelBuildReplyMessage.MESSAGE_TYPE:
                return new TunnelBuildReplyMessage(context);
            // since 0.7.10
            case VariableTunnelBuildMessage.MESSAGE_TYPE:
                return new VariableTunnelBuildMessage(context);
            // since 0.7.10
            case VariableTunnelBuildReplyMessage.MESSAGE_TYPE:
                return new VariableTunnelBuildReplyMessage(context);
            default:
                // unused
                Builder builder = _builders.get(Integer.valueOf(type));
                if (builder != null)
                    return builder.build(context);
                return new UnknownI2NPMessage(context, type);
        }
    }
}
