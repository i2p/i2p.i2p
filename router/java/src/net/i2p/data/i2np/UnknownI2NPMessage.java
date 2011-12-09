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

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.SimpleByteCache;

/**
 * This is the same as DataMessage but with a variable message type.
 * This is defined so routers can route messages they don't know about.
 * We don't extend DataMessage so that any code that does (instanceof DataMessage)
 * won't return true for this type. Load tests use DataMessage, for example.
 * See InboundMessageDistributor.
 *
 * There is no setData() method, the only way to create one of these is to
 * read it with readMessage() (i.e., it came from some other router)
 *
 * @since 0.7.12 but broken before 0.8.12
 */
public class UnknownI2NPMessage extends I2NPMessageImpl {
    private byte _data[];
    private final int _type;
    // we assume CHECKSUM_LENGTH = 1
    private byte _checksum;
    
    /** @param type 0-255 */
    public UnknownI2NPMessage(I2PAppContext context, int type) {
        super(context);
        _type = type;
    }
    
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        if (type != _type) throw new I2NPMessageException("Message type is incorrect for this message");
        if (dataSize > MAX_SIZE)
            throw new I2NPMessageException("wtf, size=" + dataSize);
        _data = new byte[dataSize];
        System.arraycopy(data, offset, _data, 0, dataSize);
    }
    
    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() { 
        if (_data == null) 
            return 0;
        else
            return _data.length;
    }

    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) {
        if (_data != null) {
            System.arraycopy(_data, 0, out, curIndex, _data.length);
            curIndex += _data.length;
        }
        return curIndex;
    }
    
    /** @return 0-255 */
    public int getType() { return _type; }
    

    /**
     *  Read the full message including the header.
     *  This is the same as I2NPMessageImpl.readBytes(), except
     *  start after the type field, and
     *  do NOT verify the checksum, but simply save it for later
     *  so it can be verified in convert() if required.
     *
     *<pre>
     *  Standard message format AFTER the type field
     *    4 byte ID
     *    8 byte expiration
     *    2 byte size
     *    1 byte checksum (saved in case we need to check later)
     *    size bytes of payload, read by readMessage()
     *</pre>
     *
     *  @param offset starting at the ID (must skip the type)
     *  @return total length of the message
     *  @since 0.8.12
     */
    public void readBytesIgnoreChecksum(byte data[], int offset) throws I2NPMessageException, IOException {
        int cur = offset;
        setUniqueId(DataHelper.fromLong(data, cur, 4));
        cur += 4;
        setMessageExpiration(DataHelper.fromLong(data, cur, DataHelper.DATE_LENGTH));
        cur += DataHelper.DATE_LENGTH;
        int size = (int)DataHelper.fromLong(data, cur, 2);
        cur += 2;
        _checksum = data[cur];
        cur++;

        if (cur + size > data.length)
            throw new I2NPMessageException("Payload is too short [" 
                                           + "data.len=" + data.length
                                           + " offset=" + offset
                                           + " cur=" + cur 
                                           + " wanted=" + size + ']');

        readMessage(data, cur, size, _type);
    }
    
    /**
     *  Attempt to convert this message to a known message class.
     *  Must have been created with readBytesIgnoreChecksum previously,
     *  as this does the delayed verification using the saved checksum.
     *
     *  Used by TunnelGatewayZeroHop.
     *
     *  @throws I2NPMessageException if the conversion fails
     *  @since 0.8.12
     */
    public I2NPMessage convert() throws I2NPMessageException {
        I2NPMessage msg = I2NPMessageImpl.createMessage(_context, _type);
        if (msg instanceof UnknownI2NPMessage)
            throw new I2NPMessageException("Unable to convert unknown type " + _type);
        byte[] calc = SimpleByteCache.acquire(Hash.HASH_LENGTH);
        _context.sha().calculateHash(_data, 0, _data.length, calc, 0);
        boolean eq = _checksum == calc[0];
        SimpleByteCache.release(calc);
        if (!eq)
            throw new I2NPMessageException("Bad checksum on " + _data.length + " byte msg type " + _type);
        try {
            msg.readMessage(_data, 0, _data.length, _type);
        } catch (IOException ioe) {
            throw new I2NPMessageException("Unable to convert type " + _type, ioe);
        }
        msg.setUniqueId(getUniqueId());
        msg.setMessageExpiration(getMessageExpiration());
        return msg;
    }

    @Override
    public int hashCode() {
        return _type + DataHelper.hashCode(_data);
    }
    
    @Override
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof UnknownI2NPMessage) ) {
            UnknownI2NPMessage msg = (UnknownI2NPMessage)object;
            return _type == msg.getType() && DataHelper.eq(_data, msg._data);
        } else {
            return false;
        }
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[UnknownI2NPMessage: ");
        buf.append("\n\tType: ").append(_type);
        buf.append("\n\tLength: ").append(calculateWrittenLength());
        buf.append("]");
        return buf.toString();
    }
}
