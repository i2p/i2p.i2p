package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.TunnelId;
import net.i2p.util.Log;

/**
 * Defines the message sent between routers for tunnel delivery
 *
 * @author jrandom
 */
public class TunnelMessage extends I2NPMessageImpl {
    private final static Log _log = new Log(TunnelMessage.class);
    public final static int MESSAGE_TYPE = 8;
    private TunnelId _tunnelId;
    private long _size;
    private byte[] _data;
    private TunnelVerificationStructure _verification;
    private byte[] _encryptedInstructions;
    
    private final static int FLAG_INCLUDESTRUCTURE = 0;
    private final static int FLAG_DONT_INCLUDESTRUCTURE = 1;
    
    public TunnelMessage(I2PAppContext context) {
        super(context);
        setTunnelId(null);
        setData(null);
        setVerificationStructure(null);
        setEncryptedDeliveryInstructions(null);
    }
    
    public TunnelId getTunnelId() { return _tunnelId; }
    public void setTunnelId(TunnelId id) { _tunnelId = id; }
    
    public byte[] getData() { return _data; }
    public void setData(byte data[]) { 
        _data = data; 
        if ( (data != null) && (_data.length <= 0) )
            throw new IllegalArgumentException("Empty tunnel payload?");
    }
    
    public TunnelVerificationStructure getVerificationStructure() { return _verification; }
    public void setVerificationStructure(TunnelVerificationStructure verification) { _verification = verification; }
    
    public byte[] getEncryptedDeliveryInstructions() { return _encryptedInstructions; }
    public void setEncryptedDeliveryInstructions(byte instructions[]) { _encryptedInstructions = instructions; }
    
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
            _tunnelId = new TunnelId();
            _tunnelId.readBytes(in);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Read tunnel message for tunnel " + _tunnelId);
            _size = DataHelper.readLong(in, 4);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Read tunnel message size: " + _size);
            if (_size < 0) throw new I2NPMessageException("Invalid size in the structure: " + _size);
            _data = new byte[(int)_size];
            int read = read(in, _data);
            if (read != _size)
                throw new I2NPMessageException("Incorrect number of bytes read (" + read + ", expected " + _size);
            int includeVerification = (int)DataHelper.readLong(in, 1);
            if (includeVerification == FLAG_INCLUDESTRUCTURE) {
                _verification = new TunnelVerificationStructure();
                _verification.readBytes(in);
                int len = (int)DataHelper.readLong(in, 2);
                _encryptedInstructions = new byte[len];
                read = read(in, _encryptedInstructions);
                if (read != len)
                    throw new I2NPMessageException("Incorrect number of bytes read for instructions (" + read + ", expected " + len + ")");
            }
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Unable to load the message data", dfe);
        }
    }
    
    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() { 
        int length = 0;
        length += 4; // tunnelId
        length += 4; // data length
        length += _data.length;
        if ( (_verification == null) || (_encryptedInstructions == null) ) {
            length += 1; // include verification?
        } else {
            length += 1; // include verification?
            length += Hash.HASH_LENGTH + Signature.SIGNATURE_BYTES;
            length += 2; // instructions length
            length += _encryptedInstructions.length;
        }
        return length;
    }
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        if ( (_tunnelId == null) || (_data == null) )
            throw new I2NPMessageException("Not enough data to write out (id=" + _tunnelId + " data=" + _data + ")");
        if (_data.length <= 0) 
            throw new I2NPMessageException("Not enough data to write out (data.length=" + _data.length + ")");
        
        byte id[] = DataHelper.toLong(4, _tunnelId.getTunnelId());
        System.arraycopy(id, 0, out, curIndex, 4);
        curIndex += 4;
        byte len[] = DataHelper.toLong(4, _data.length);
        System.arraycopy(len, 0, out, curIndex, 4);
        curIndex += 4;
        System.arraycopy(_data, 0, out, curIndex, _data.length);
        curIndex += _data.length;
        if ( (_verification == null) || (_encryptedInstructions == null) ) {
            byte flag[] = DataHelper.toLong(1, FLAG_DONT_INCLUDESTRUCTURE);
            out[curIndex++] = flag[0];
        } else {
            byte flag[] = DataHelper.toLong(1, FLAG_INCLUDESTRUCTURE);
            out[curIndex++] = flag[0];
            System.arraycopy(_verification.getMessageHash().getData(), 0, out, curIndex, Hash.HASH_LENGTH);
            curIndex += Hash.HASH_LENGTH;
            System.arraycopy(_verification.getAuthorizationSignature().getData(), 0, out, curIndex, Signature.SIGNATURE_BYTES);
            curIndex += Signature.SIGNATURE_BYTES;
            len = DataHelper.toLong(2, _encryptedInstructions.length);
            System.arraycopy(len, 0, out, curIndex, 2);
            curIndex += 2;
            System.arraycopy(_encryptedInstructions, 0, out, curIndex, _encryptedInstructions.length);
            curIndex += _encryptedInstructions.length;
        }
        return curIndex;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public int hashCode() {
        return DataHelper.hashCode(getTunnelId()) +
               DataHelper.hashCode(_data) +
               DataHelper.hashCode(getVerificationStructure()) +
               DataHelper.hashCode(getEncryptedDeliveryInstructions());
    }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof TunnelMessage) ) {
            TunnelMessage msg = (TunnelMessage)object;
            return DataHelper.eq(getTunnelId(),msg.getTunnelId()) &&
                   DataHelper.eq(getVerificationStructure(),msg.getVerificationStructure()) &&
                   DataHelper.eq(getData(),msg.getData()) &&
                   DataHelper.eq(getEncryptedDeliveryInstructions(), msg.getEncryptedDeliveryInstructions());
        } else {
            return false;
        }
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("[TunnelMessage: ");
        buf.append("\n\tMessageId: ").append(getUniqueId());
        buf.append("\n\tExpiration: ").append(getMessageExpiration());
        buf.append("\n\tTunnel ID: ").append(getTunnelId());
        buf.append("\n\tVerification Structure: ").append(getVerificationStructure());
        buf.append("\n\tEncrypted Instructions: ").append(getEncryptedDeliveryInstructions());
        buf.append("\n\tData size: ").append(getData().length);
        buf.append("]");
        return buf.toString();
    }
}
