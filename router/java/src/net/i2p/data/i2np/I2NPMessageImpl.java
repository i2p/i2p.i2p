package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;

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
    private Date _expiration;
    private long _uniqueId;
    private byte _data[];
    
    public final static long DEFAULT_EXPIRATION_MS = 1*60*1000; // 1 minute by default
    
    public I2NPMessageImpl(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(I2NPMessageImpl.class);
        _expiration = new Date(_context.clock().now() + DEFAULT_EXPIRATION_MS);
        _uniqueId = _context.random().nextLong(MAX_ID_VALUE);
        _context.statManager().createRateStat("i2np.writeTime", "How long it takes to write an I2NP message", "I2NP", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("i2np.readTime", "How long it takes to read an I2NP message", "I2NP", new long[] { 10*60*1000, 60*60*1000 });
    }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        try {
            readBytes(in, -1, new byte[1024]);
        } catch (I2NPMessageException ime) {
            throw new DataFormatException("Bad bytes", ime);
        }
    }
    public void readBytes(InputStream in, int type, byte buffer[]) throws I2NPMessageException, IOException {
        try {
            if (type < 0)
                type = (int)DataHelper.readLong(in, 1);
            _uniqueId = DataHelper.readLong(in, 4);
            _expiration = DataHelper.readDate(in);
            int size = (int)DataHelper.readLong(in, 2);
            Hash h = new Hash();
            h.readBytes(in);
            if (buffer.length < size) {
                if (size > 64*1024) throw new I2NPMessageException("size=" + size);
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
            if (!calc.equals(h))
                throw new I2NPMessageException("Hash does not match");

            long start = _context.clock().now();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Reading bytes: type = " + type + " / uniqueId : " + _uniqueId + " / expiration : " + _expiration);
            readMessage(buffer, 0, size, type);
            long time = _context.clock().now() - start;
            if (time > 50)
                _context.statManager().addRateData("i2np.readTime", time, time);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error reading the message header", dfe);
        }
    }
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        int size = getMessageSize();
        if (size < 47) throw new DataFormatException("Unable to build the message");
        byte buf[] = new byte[size];
        int read = toByteArray(buf);
        if (read < 0)
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
    public Date getMessageExpiration() { return _expiration; }
    public void setMessageExpiration(Date exp) { _expiration = exp; }
    
    public synchronized int getMessageSize() { 
        return calculateWrittenLength()+47; // 47 bytes in the header
    }
    
    public byte[] toByteArray() {
        byte data[] = new byte[getMessageSize()];
        int written = toByteArray(data);
        if (written != data.length) {
            _log.error("Error writing out " + data.length + " for " + getClass().getName());
            return null;
        }
        return data;
    }
    
    public int toByteArray(byte buffer[]) {
        long start = _context.clock().now();

        byte prefix[][] = new byte[][] { DataHelper.toLong(1, getType()), 
                                         DataHelper.toLong(4, _uniqueId),
                                         DataHelper.toDate(_expiration),
                                         new byte[2], 
                                         new byte[Hash.HASH_LENGTH]};
        byte suffix[][] = new byte[][] { };
        try {
            int writtenLen = toByteArray(buffer, prefix, suffix);

            int prefixLen = 1+4+8+2+Hash.HASH_LENGTH;
            int suffixLen = 0;
            int payloadLen = writtenLen  - prefixLen - suffixLen;
            Hash h = _context.sha().calculateHash(buffer, prefixLen, payloadLen);

            byte len[] = DataHelper.toLong(2, payloadLen);
            buffer[1+4+8] = len[0];
            buffer[1+4+8+1] = len[1];
            for (int i = 0; i < Hash.HASH_LENGTH; i++)
                System.arraycopy(h.getData(), 0, buffer, 1+4+8+2, Hash.HASH_LENGTH);

            long time = _context.clock().now() - start;
            if (time > 50)
                _context.statManager().addRateData("i2np.writeTime", time, time);

            return writtenLen;                     
        } catch (I2NPMessageException ime) {
            _context.logManager().getLog(getClass()).error("Error writing", ime);
            throw new IllegalStateException("Unable to serialize the message: " + ime.getMessage());
        }
    }
    
    /** calculate the message body's length (not including the header and footer */
    protected abstract int calculateWrittenLength();
    /** 
     * write the message body to the output array, starting at the given index.
     * @return the index into the array after the last byte written
     */
    protected abstract int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException;
    
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
}
