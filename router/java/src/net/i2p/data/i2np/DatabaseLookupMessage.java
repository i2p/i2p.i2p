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
    private Hash _fromHash;
    private TunnelId _replyTunnel;
    private Set _dontIncludePeers;
    
    private static volatile long _currentLookupPeriod;
    private static volatile int _currentLookupCount;
    // if we try to send over 20 netDb lookups in 10 seconds, we're acting up
    private static final long LOOKUP_THROTTLE_PERIOD = 10*1000;
    private static final long LOOKUP_THROTTLE_MAX = 20;
    
    public DatabaseLookupMessage(I2PAppContext context) {
        super(context);
        setSearchKey(null);
        setFrom(null);
        setDontIncludePeers(null);
        
        context.statManager().createRateStat("router.throttleNetDbDoSSend", "How many netDb lookup messages we are sending during a period with a DoS detected", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
    }
    
    private static boolean detectDoS(I2PAppContext context) {
        // now lets check for DoS
        long now = context.clock().now();
        if (_currentLookupPeriod + LOOKUP_THROTTLE_PERIOD > now) {
            // same period, check for DoS
            _currentLookupCount++;
            if (_currentLookupCount >= LOOKUP_THROTTLE_MAX) {
                context.statManager().addRateData("router.throttleNetDbDoSSend", _currentLookupCount, 0);
                return true;
            } else {
                // no DoS, at least, not yet
                return false;
            }
        } else {
            // on to the next period, reset counter, no DoS
            // (no, I'm not worried about concurrency here)
            _currentLookupPeriod = now;
            _currentLookupCount = 1;
            return true;
        }
    }
    
    /**
     * Defines the key being searched for
     */
    public Hash getSearchKey() { return _key; }
    public void setSearchKey(Hash key) { _key = key; }
    
    /**
     * Contains the router who requested this lookup
     *
     */
    public Hash getFrom() { return _fromHash; }
    public void setFrom(Hash from) { _fromHash = from; }
    
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
            _fromHash = new Hash();
            _fromHash.readBytes(in);
            Boolean val = DataHelper.readBoolean(in);
            if (val == null) 
                throw new I2NPMessageException("Tunnel must be explicitly specified (or not)");
            boolean tunnelSpecified = val.booleanValue();
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
        if (_fromHash == null) throw new I2NPMessageException("From address not specified");
        
        // we do this in the writeMessage so we know that we have all the data
        boolean isDoS = detectDoS(_context);
        if (isDoS) {
            _log.log(Log.CRIT, "Are we flooding the network with NetDb lookup messages for " 
                     + _key.toBase64() + " (reply through " + _fromHash + " / " + _replyTunnel + ")",
                     new Exception("Flood cause"));
        }

        
        ByteArrayOutputStream os = new ByteArrayOutputStream(32);
        try {
            _key.writeBytes(os);
            _fromHash.writeBytes(os);
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
