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
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.util.Log;

/**
 * Defines the message a router sends to another router to search for a
 * key in the network database.
 *
 * @author jrandom
 */
public class DatabaseLookupMessage extends I2NPMessageImpl {
    private final static Log _log = new Log(DatabaseLookupMessage.class);
    public final static int MESSAGE_TYPE = 2;
    private Hash _key;
    private RouterInfo _from;
    private TunnelId _replyTunnel;
    private Set _dontIncludePeers;
    
    public DatabaseLookupMessage(I2PAppContext context) {
        super(context);
        setSearchKey(null);
        setFrom(null);
        setDontIncludePeers(null);
    }
    
    /**
     * Defines the key being searched for
     */
    public Hash getSearchKey() { return _key; }
    public void setSearchKey(Hash key) { _key = key; }
    
    /**
     * Contains the current router info of the router who requested this lookup
     *
     */
    public RouterInfo getFrom() { return _from; }
    public void setFrom(RouterInfo from) { _from = from; }
    
    /**
     * Contains the tunnel ID a reply should be sent to
     *
     */
    public TunnelId getReplyTunnel() { return _replyTunnel; }
    public void setReplyTunnel(TunnelId replyTunnel) { _replyTunnel = replyTunnel; }
    
    /**
     * Set of peers that a lookup reply should NOT include
     *
     * @return Set of Hash objects, each of which is the H(routerIdentity) to skip
     */
    public Set getDontIncludePeers() { return _dontIncludePeers; }
    public void setDontIncludePeers(Set peers) {
        if (peers != null)
            _dontIncludePeers = new HashSet(peers);
        else
            _dontIncludePeers = null;
    }
    
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
            _key = new Hash();
            _key.readBytes(in);
            _from = new RouterInfo();
            _from.readBytes(in);
            boolean tunnelSpecified = DataHelper.readBoolean(in).booleanValue();
            if (tunnelSpecified) {
                _replyTunnel = new TunnelId();
                _replyTunnel.readBytes(in);
            }
            int numPeers = (int)DataHelper.readLong(in, 2);
            if ( (numPeers < 0) || (numPeers >= (1<<16) ) )
                throw new DataFormatException("Invalid number of peers - " + numPeers);
            Set peers = new HashSet(numPeers);
            for (int i = 0; i < numPeers; i++) {
                Hash peer = new Hash();
                peer.readBytes(in);
                peers.add(peer);
            }
            _dontIncludePeers = peers;
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Unable to load the message data", dfe);
        }
    }
    
    protected byte[] writeMessage() throws I2NPMessageException, IOException {
        if (_key == null) throw new I2NPMessageException("Key being searched for not specified");
        if (_from == null) throw new I2NPMessageException("From address not specified");
        
        ByteArrayOutputStream os = new ByteArrayOutputStream(32);
        try {
            _key.writeBytes(os);
            _from.writeBytes(os);
            if (_replyTunnel != null) {
                DataHelper.writeBoolean(os, Boolean.TRUE);
                _replyTunnel.writeBytes(os);
            } else {
                DataHelper.writeBoolean(os, Boolean.FALSE);
            }
            if ( (_dontIncludePeers == null) || (_dontIncludePeers.size() <= 0) ) {
                DataHelper.writeLong(os, 2, 0);
            } else {
                DataHelper.writeLong(os, 2, _dontIncludePeers.size());
                for (Iterator iter = _dontIncludePeers.iterator(); iter.hasNext(); ) {
                    Hash peer = (Hash)iter.next();
                    peer.writeBytes(os);
                }
            }
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public int hashCode() {
        return DataHelper.hashCode(getSearchKey()) +
               DataHelper.hashCode(getFrom()) +
               DataHelper.hashCode(getReplyTunnel()) +
               DataHelper.hashCode(_dontIncludePeers);
    }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DatabaseLookupMessage) ) {
            DatabaseLookupMessage msg = (DatabaseLookupMessage)object;
            return DataHelper.eq(getSearchKey(),msg.getSearchKey()) &&
                   DataHelper.eq(getFrom(),msg.getFrom()) &&
                   DataHelper.eq(getReplyTunnel(),msg.getReplyTunnel()) &&
                   DataHelper.eq(_dontIncludePeers,msg.getDontIncludePeers());
        } else {
            return false;
        }
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("[DatabaseLookupMessage: ");
        buf.append("\n\tSearch Key: ").append(getSearchKey());
        buf.append("\n\tFrom: ").append(getFrom());
        buf.append("\n\tReply Tunnel: ").append(getReplyTunnel());
        buf.append("\n\tDont Include Peers: ").append(getDontIncludePeers());
        buf.append("]");
        return buf.toString();
    }
}
