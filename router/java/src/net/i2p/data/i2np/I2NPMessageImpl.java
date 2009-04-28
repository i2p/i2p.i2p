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
import java.util.HashMap;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Defines the base message implementation.
 *
 * @author jrandom
 */
public abstract class I2NPMessageImpl extends DataStructureImpl implements I2NPMessage {
    private Log _log;
    protected I2PAppContext _context;
    private long _expiration;
    private long _uniqueId;
    private boolean _written;
    private boolean _read;
    
    public final static long DEFAULT_EXPIRATION_MS = 1*60*1000; // 1 minute by default
    public final static int CHECKSUM_LENGTH = 1; //Hash.HASH_LENGTH;
    
    private static final boolean RAW_FULL_SIZE = false;
    
    /** unsynchronized as its pretty much read only (except at startup) */
    private static final Map _builders = new HashMap(8);
    public static final void registerBuilder(Builder builder, int type) { _builders.put(Integer.valueOf(type), builder); }
    /** interface for extending the types of messages handled */
    public interface Builder {
        /** instantiate a new I2NPMessage to be populated shortly */
        public I2NPMessage build(I2PAppContext ctx);
    }
    
    public I2NPMessageImpl(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(I2NPMessageImpl.class);
        _expiration = _context.clock().now() + DEFAULT_EXPIRATION_MS;
        _uniqueId = _context.random().nextLong(MAX_ID_VALUE);
        _written = false;
        _read = false;
        //_context.statManager().createRateStat("i2np.writeTime", "How long it takes to write an I2NP message", "I2NP", new long[] { 10*60*1000, 60*60*1000 });
        //_context.statManager().createRateStat("i2np.readTime", "How long it takes to read an I2NP message", "I2NP", new long[] { 10*60*1000, 60*60*1000 });
    }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        try {
            readBytes(in, -1, new byte[1024]);
        } catch (I2NPMessageException ime) {
            throw new DataFormatException("Bad bytes", ime);
        }
    }
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
            
            Hash calc = _context.sha().calculateHash(buffer, 0, size);
            //boolean eq = calc.equals(h);
            boolean eq = DataHelper.eq(checksum, 0, calc.getData(), 0, CHECKSUM_LENGTH);
            if (!eq)
                throw new I2NPMessageException("Hash does not match for " + getClass().getName());

            long start = _context.clock().now();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Reading bytes: type = " + type + " / uniqueId : " + _uniqueId + " / expiration : " + _expiration);
            readMessage(buffer, 0, size, type);
            //long time = _context.clock().now() - start;
            //if (time > 50)
            //    _context.statManager().addRateData("i2np.readTime", time, time);
            _read = true;
            return size + Hash.HASH_LENGTH + 1 + 4 + DataHelper.DATE_LENGTH;
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error reading the message header", dfe);
        }
    }
    public int readBytes(byte data[], int type, int offset) throws I2NPMessageException, IOException {
        int cur = offset;
        if (type < 0) {
            type = (int)DataHelper.fromLong(data, cur, 1);
            cur++;
        }
        _uniqueId = DataHelper.fromLong(data, cur, 4);
        cur += 4;
        _expiration = DataHelper.fromLong(data, cur, DataHelper.DATE_LENGTH);
        cur += DataHelper.DATE_LENGTH;
        int size = (int)DataHelper.fromLong(data, cur, 2);
        cur += 2;
        //Hash h = new Hash();
        byte hdata[] = new byte[CHECKSUM_LENGTH];
        System.arraycopy(data, cur, hdata, 0, CHECKSUM_LENGTH);
        cur += CHECKSUM_LENGTH;
        //h.setData(hdata);

        if (cur + size > data.length)
            throw new I2NPMessageException("Payload is too short [" 
                                           + "data.len=" + data.length
                                           + " offset=" + offset
                                           + " cur=" + cur 
                                           + " wanted=" + size + "]: " + getClass().getName());

        Hash calc = _context.sha().calculateHash(data, cur, size);
        //boolean eq = calc.equals(h);
        boolean eq = DataHelper.eq(hdata, 0, calc.getData(), 0, CHECKSUM_LENGTH);
        if (!eq)
            throw new I2NPMessageException("Hash does not match for " + getClass().getName());

        long start = _context.clock().now();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Reading bytes: type = " + type + " / uniqueId : " + _uniqueId + " / expiration : " + _expiration);
        readMessage(data, cur, size, type);
        cur += size;
        //long time = _context.clock().now() - start;
        //if (time > 50)
        //    _context.statManager().addRateData("i2np.readTime", time, time);
        _read = true;
        return cur - offset;
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        int size = getMessageSize();
        if (size < 15 + CHECKSUM_LENGTH) throw new DataFormatException("Unable to build the message");
        byte buf[] = new byte[size];
        int read = toByteArray(buf);
        if (read < 0) throw new DataFormatException("Unable to build the message");
        out.write(buf, 0, read);
    }
    
    /**
     * Replay resistent message Id
     */
    public long getUniqueId() { return _uniqueId; }
    public void setUniqueId(long id) { _uniqueId = id; }
    
    /**
     * Date after which the message should be dropped (and the associated uniqueId forgotten)
     *
     */
    public long getMessageExpiration() { return _expiration; }
    public void setMessageExpiration(long exp) { _expiration = exp; }
    
    public synchronized int getMessageSize() { 
        return calculateWrittenLength()+15 + CHECKSUM_LENGTH; // 16 bytes in the header
    }
    public synchronized int getRawMessageSize() { 
        if (RAW_FULL_SIZE) 
            return getMessageSize();
        else
            return calculateWrittenLength()+5;
    }
    
    @Override
    public byte[] toByteArray() {
        byte data[] = new byte[getMessageSize()];
        int written = toByteArray(data);
        if (written != data.length) {
            _log.log(Log.CRIT, "Error writing out " + data.length + " (written: " + written + ", msgSize: " + getMessageSize() +
                               ", writtenLen: " + calculateWrittenLength() + ") for " + getClass().getName());
            return null;
        }
        return data;
    }
    
    public int toByteArray(byte buffer[]) {
        long start = _context.clock().now();

        int prefixLen = 1 // type
                        + 4 // uniqueId
                        + DataHelper.DATE_LENGTH // expiration
                        + 2 // payload length
                        + CHECKSUM_LENGTH; // walnuts
        //byte prefix[][] = new byte[][] { DataHelper.toLong(1, getType()), 
        //                                 DataHelper.toLong(4, _uniqueId),
        //                                 DataHelper.toLong(DataHelper.DATE_LENGTH, _expiration),
        //                                 new byte[2], 
        //                                 new byte[CHECKSUM_LENGTH]};
        //byte suffix[][] = new byte[][] { };
        try {
            int writtenLen = writeMessageBody(buffer, prefixLen);
            int payloadLen = writtenLen - prefixLen;
            Hash h = _context.sha().calculateHash(buffer, prefixLen, payloadLen);

            int off = 0;
            DataHelper.toLong(buffer, off, 1, getType());
            off += 1;
            DataHelper.toLong(buffer, off, 4, _uniqueId);
            off += 4;
            DataHelper.toLong(buffer, off, DataHelper.DATE_LENGTH, _expiration);
            off += DataHelper.DATE_LENGTH;
            DataHelper.toLong(buffer, off, 2, payloadLen);
            off += 2;
            System.arraycopy(h.getData(), 0, buffer, off, CHECKSUM_LENGTH);

            //long time = _context.clock().now() - start;
            //if (time > 50)
            //    _context.statManager().addRateData("i2np.writeTime", time, time);

            return writtenLen;                     
        } catch (I2NPMessageException ime) {
            _context.logManager().getLog(getClass()).log(Log.CRIT, "Error writing", ime);
            throw new IllegalStateException("Unable to serialize the message (" + getClass().getName() 
                                            + "): " + ime.getMessage());
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

    
    public int toRawByteArray(byte buffer[]) {
        verifyUnwritten();
        if (RAW_FULL_SIZE)
            return toByteArray(buffer);
        try {
            int off = 0;
            DataHelper.toLong(buffer, off, 1, getType());
            off += 1;
            DataHelper.toLong(buffer, off, 4, _expiration/1000); // seconds
            off += 4;
            return writeMessageBody(buffer, off);
        } catch (I2NPMessageException ime) {
            _context.logManager().getLog(getClass()).log(Log.CRIT, "Error writing", ime);
            throw new IllegalStateException("Unable to serialize the message (" + getClass().getName() 
                                            + "): " + ime.getMessage());
        } finally {
            written();
        }
    }

    public void readMessage(byte data[], int offset, int dataSize, int type, I2NPMessageHandler handler) throws I2NPMessageException, IOException {
        // ignore the handler (overridden in subclasses if necessary
        try {
            readMessage(data, offset, dataSize, type);
        } catch (IllegalArgumentException iae) {
            throw new I2NPMessageException("Error reading the message", iae);
        }
    }

    
    public static I2NPMessage fromRawByteArray(I2PAppContext ctx, byte buffer[], int offset, int len) throws I2NPMessageException {
        return fromRawByteArray(ctx, buffer, offset, len, new I2NPMessageHandler(ctx));
    }
    public static I2NPMessage fromRawByteArray(I2PAppContext ctx, byte buffer[], int offset, int len, I2NPMessageHandler handler) throws I2NPMessageException {
        int type = (int)DataHelper.fromLong(buffer, offset, 1);
        offset++;
        I2NPMessageImpl msg = (I2NPMessageImpl)createMessage(ctx, type);
        if (msg == null) 
            throw new I2NPMessageException("Unknown message type: " + type);
        if (RAW_FULL_SIZE) {
            try {
                msg.readBytes(buffer, type, offset);
            } catch (IOException ioe) {
                throw new I2NPMessageException("Error reading the " + msg, ioe);
            }
            msg.read();
            return msg;
        }

        try {
            long expiration = DataHelper.fromLong(buffer, offset, 4) * 1000; // seconds
            offset += 4;
            int dataSize = len - 1 - 4;
            msg.readMessage(buffer, offset, dataSize, type, handler);
            msg.setMessageExpiration(expiration);
            msg.read();
            return msg;
        } catch (IOException ioe) {
            throw new I2NPMessageException("IO error reading raw message", ioe);
        } catch (IllegalArgumentException iae) {
            throw new I2NPMessageException("Corrupt message (negative expiration)", iae);
        }
    }

    protected void verifyUnwritten() { 
        if (_written) throw new IllegalStateException("Already written"); 
    }
    protected void written() { _written = true; }
    protected void read() { _read = true; }
    
    /**
     * Yes, this is fairly ugly, but its the only place it ever happens.
     *
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
            case DateMessage.MESSAGE_TYPE:
                return new DateMessage(context);
            case GarlicMessage.MESSAGE_TYPE:
                return new GarlicMessage(context);
            case TunnelDataMessage.MESSAGE_TYPE:
                return new TunnelDataMessage(context);
            case TunnelGatewayMessage.MESSAGE_TYPE:
                return new TunnelGatewayMessage(context);
            case DataMessage.MESSAGE_TYPE:
                return new DataMessage(context);
            case TunnelCreateMessage.MESSAGE_TYPE:
                return new TunnelCreateMessage(context);
            case TunnelCreateStatusMessage.MESSAGE_TYPE:
                return new TunnelCreateStatusMessage(context);
            case TunnelBuildMessage.MESSAGE_TYPE:
                return new TunnelBuildMessage(context);
            case TunnelBuildReplyMessage.MESSAGE_TYPE:
                return new TunnelBuildReplyMessage(context);
            default:
                Builder builder = (Builder)_builders.get(Integer.valueOf(type));
                if (builder == null)
                    return null;
                else
                    return builder.build(context);
        }
    }
}
