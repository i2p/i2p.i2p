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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.util.Log;
import net.i2p.I2PAppContext;

/**
 * Defines the message a router sends to another router to test the network
 * database reachability, as well as the reply message sent back.
 *
 * @author jrandom
 */
public class DatabaseStoreMessage extends I2NPMessageImpl {
    private final static Log _log = new Log(DatabaseStoreMessage.class);
    public final static int MESSAGE_TYPE = 1;
    private Hash _key;
    private int _type;
    private LeaseSet _leaseSet;
    private RouterInfo _info;
    
    public final static int KEY_TYPE_ROUTERINFO = 0;
    public final static int KEY_TYPE_LEASESET = 1;
    
    public DatabaseStoreMessage(I2PAppContext context) {
        super(context);
        setValueType(-1);
        setKey(null);
        setLeaseSet(null);
        setRouterInfo(null);
    }
    
    /**
     * Defines the key in the network database being stored
     *
     */
    public Hash getKey() { return _key; }
    public void setKey(Hash key) { _key = key; }
    
    /**
     * Defines the router info value in the network database being stored
     *
     */
    public RouterInfo getRouterInfo() { return _info; }
    public void setRouterInfo(RouterInfo routerInfo) {
        _info = routerInfo;
        if (_info != null)
            setValueType(KEY_TYPE_ROUTERINFO);
    }
    
    /**
     * Defines the lease set value in the network database being stored
     *
     */
    public LeaseSet getLeaseSet() { return _leaseSet; }
    public void setLeaseSet(LeaseSet leaseSet) {
        _leaseSet = leaseSet;
        if (_leaseSet != null)
            setValueType(KEY_TYPE_LEASESET);
    }
    
    /**
     * Defines type of key being stored in the network database -
     * either KEY_TYPE_ROUTERINFO or KEY_TYPE_LEASESET
     *
     */
    public int getValueType() { return _type; }
    public void setValueType(int type) { _type = type; }
    
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
            _key = new Hash();
            _key.readBytes(in);
            _log.debug("Hash read: " + _key.toBase64());
            _type = (int)DataHelper.readLong(in, 1);
            if (_type == KEY_TYPE_LEASESET) {
                _leaseSet = new LeaseSet();
                _leaseSet.readBytes(in);
            } else if (_type == KEY_TYPE_ROUTERINFO) {
                _info = new RouterInfo();
                int compressedSize = (int)DataHelper.readLong(in, 2);
                byte compressed[] = new byte[compressedSize];
                int read = DataHelper.read(in, compressed);
                if (read != compressedSize)
                    throw new I2NPMessageException("Invalid compressed data size");
                ByteArrayInputStream bais = new ByteArrayInputStream(DataHelper.decompress(compressed));
                _info.readBytes(bais);
            } else {
                throw new I2NPMessageException("Invalid type of key read from the structure - " + _type);
            }
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Unable to load the message data", dfe);
        }
    }
    
    protected byte[] writeMessage() throws I2NPMessageException, IOException {
        if (_key == null) throw new I2NPMessageException("Invalid key");
        if ( (_type != KEY_TYPE_LEASESET) && (_type != KEY_TYPE_ROUTERINFO) ) throw new I2NPMessageException("Invalid key type");
        if ( (_type == KEY_TYPE_LEASESET) && (_leaseSet == null) ) throw new I2NPMessageException("Missing lease set");
        if ( (_type == KEY_TYPE_ROUTERINFO) && (_info == null) ) throw new I2NPMessageException("Missing router info");
        
        ByteArrayOutputStream os = new ByteArrayOutputStream(256);
        try {
            _key.writeBytes(os);
            DataHelper.writeLong(os, 1, _type);
            if (_type == KEY_TYPE_LEASESET) {
                _leaseSet.writeBytes(os);
            } else if (_type == KEY_TYPE_ROUTERINFO) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(4*1024);
                _info.writeBytes(baos);
                byte uncompressed[] = baos.toByteArray();
                byte compressed[] = DataHelper.compress(uncompressed);
                DataHelper.writeLong(os, 2, compressed.length);
                os.write(compressed);
            }
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public int hashCode() {
        return DataHelper.hashCode(getKey()) +
               DataHelper.hashCode(getLeaseSet()) +
               DataHelper.hashCode(getRouterInfo()) +
               getValueType();
    }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DatabaseStoreMessage) ) {
            DatabaseStoreMessage msg = (DatabaseStoreMessage)object;
            return DataHelper.eq(getKey(),msg.getKey()) &&
                   DataHelper.eq(getLeaseSet(),msg.getLeaseSet()) &&
                   DataHelper.eq(getRouterInfo(),msg.getRouterInfo()) &&
                   DataHelper.eq(getValueType(),msg.getValueType());
        } else {
            return false;
        }
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("[DatabaseStoreMessage: ");
        buf.append("\n\tExpiration: ").append(getMessageExpiration());
        buf.append("\n\tUnique ID: ").append(getUniqueId());
        buf.append("\n\tKey: ").append(getKey());
        buf.append("\n\tValue Type: ").append(getValueType());
        buf.append("\n\tRouter Info: ").append(getRouterInfo());
        buf.append("\n\tLease Set: ").append(getLeaseSet());
        buf.append("]");
        return buf.toString();
    }
}
