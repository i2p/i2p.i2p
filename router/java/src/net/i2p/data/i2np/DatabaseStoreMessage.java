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

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.util.Log;

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
    private long _replyToken;
    private TunnelId _replyTunnel;
    private Hash _replyGateway;
    
    public final static int KEY_TYPE_ROUTERINFO = 0;
    public final static int KEY_TYPE_LEASESET = 1;
    
    public DatabaseStoreMessage(I2PAppContext context) {
        super(context);
        setValueType(-1);
        setKey(null);
        setLeaseSet(null);
        setRouterInfo(null);
        setReplyToken(0);
        setReplyTunnel(null);
        setReplyGateway(null);
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
    
    /**
     * If a reply is desired, this token specifies the message ID that should
     * be used for a DeliveryStatusMessage to be sent to the reply tunnel on the
     * reply gateway.  
     *
     * @return positive reply token ID, or 0 if no reply is necessary.
     */
    public long getReplyToken() { return _replyToken; }
    /**
     * Update the reply token.
     *
     * @throws IllegalArgumentException if the token is out of range (min=0, max=I2NPMessage.MAX_ID_VALUE)
     */
    public void setReplyToken(long token) throws IllegalArgumentException { 
        if (token > I2NPMessage.MAX_ID_VALUE)
            throw new IllegalArgumentException("Token too large: " + token + " (max=" + I2NPMessage.MAX_ID_VALUE + ")");
        else if (token < 0) 
            throw new IllegalArgumentException("Token too small: " + token);
        _replyToken = token; 
    }
    
    public TunnelId getReplyTunnel() { return _replyTunnel; } 
    public void setReplyTunnel(TunnelId id) { _replyTunnel = id; }
    
    public Hash getReplyGateway() { return _replyGateway; }
    public void setReplyGateway(Hash peer) { _replyGateway = peer; }
    
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
            _key = new Hash();
            _key.readBytes(in);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Hash read: " + _key.toBase64());
            _type = (int)DataHelper.readLong(in, 1);
            _replyToken = DataHelper.readLong(in, 4);
            if (_replyToken > 0) {
                _replyTunnel = new TunnelId();
                _replyTunnel.readBytes(in);
                _replyGateway = new Hash();
                _replyGateway.readBytes(in);
            } else {
                _replyTunnel = null;
                _replyGateway = null;
            }
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
            DataHelper.writeLong(os, 4, _replyToken);
            if (_replyToken > 0) {
                _replyTunnel.writeBytes(os);
                _replyGateway.writeBytes(os);
            } else {
                // noop
            }
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
               getValueType() +
               (int)getReplyToken() +
               DataHelper.hashCode(getReplyTunnel()) +
               DataHelper.hashCode(getReplyGateway());
    }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DatabaseStoreMessage) ) {
            DatabaseStoreMessage msg = (DatabaseStoreMessage)object;
            return DataHelper.eq(getKey(),msg.getKey()) &&
                   DataHelper.eq(getLeaseSet(),msg.getLeaseSet()) &&
                   DataHelper.eq(getRouterInfo(),msg.getRouterInfo()) &&
                   DataHelper.eq(getValueType(),msg.getValueType()) &&
                   getReplyToken() == msg.getReplyToken() &&
                   DataHelper.eq(getReplyTunnel(), msg.getReplyTunnel()) &&
                   DataHelper.eq(getReplyGateway(), msg.getReplyGateway());
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
        buf.append("\n\tReply token: ").append(getReplyToken());
        buf.append("\n\tReply tunnel: ").append(getReplyTunnel());
        buf.append("\n\tReply gateway: ").append(getReplyGateway());
        buf.append("]");
        return buf.toString();
    }
}
