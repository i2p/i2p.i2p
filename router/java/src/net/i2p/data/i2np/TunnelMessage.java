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
    public void setData(byte data[]) { _data = data; }
    
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
    
    protected byte[] writeMessage() throws I2NPMessageException, IOException {
        if ( (_tunnelId == null) || (_data == null) || (_data.length <= 0) )
            throw new I2NPMessageException("Not enough data to write out");
        
        ByteArrayOutputStream os = new ByteArrayOutputStream(4096);
        try {
            _tunnelId.writeBytes(os);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Writing tunnel message for tunnel " + _tunnelId);
            DataHelper.writeLong(os, 4, _data.length);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Writing tunnel message length: " + _data.length);
            os.write(_data);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Writing tunnel message data");
            if ( (_verification == null) || (_encryptedInstructions == null) ) {
                DataHelper.writeLong(os, 1, FLAG_DONT_INCLUDESTRUCTURE);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Writing DontIncludeStructure flag");
            } else {
                DataHelper.writeLong(os, 1, FLAG_INCLUDESTRUCTURE);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Writing IncludeStructure flag, then the verification structure, then the " +
                               "E(instr).length [" + _encryptedInstructions.length + "], then the E(instr)");
                _verification.writeBytes(os);
                DataHelper.writeLong(os, 2, _encryptedInstructions.length);
                os.write(_encryptedInstructions);
            }
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error writing out the message data", dfe);
        }
        byte rv[] = os.toByteArray();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Overall data being written: " + rv.length);
        return rv;
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
