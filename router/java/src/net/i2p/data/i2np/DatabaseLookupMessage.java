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
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.I2PAppContext;
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
    
    private static volatile long _currentLookupPeriod = 0;
    private static volatile int _currentLookupCount = 0;
    // if we try to send over 20 netDb lookups in 10 seconds, we're acting up
    private static final long LOOKUP_THROTTLE_PERIOD = 10*1000;
    private static final long LOOKUP_THROTTLE_MAX = 50;
    
    public DatabaseLookupMessage(I2PAppContext context) {
        this(context, false);
    }
    public DatabaseLookupMessage(I2PAppContext context, boolean locallyCreated) {
        super(context);
        //setSearchKey(null);
        //setFrom(null);
        //setDontIncludePeers(null);
        
        context.statManager().createRateStat("router.throttleNetDbDoSSend", "How many netDb lookup messages we are sending during a period with a DoS detected", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
    
        // only check DoS generation if we are creating the message...
        if (locallyCreated) {
            // we do this in the writeMessage so we know that we have all the data
            int dosCount = detectDoS(context);
            if (dosCount > 0) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Are we flooding the network with NetDb messages?  (" + dosCount 
                              + " messages so far)", new Exception("Flood cause"));
            }
        }
    }
    
    /**
     * Return number of netDb messages in this period, if flood, else 0
     *
     */
    private static int detectDoS(I2PAppContext context) {
        int count = _currentLookupCount++;
        // now lets check for DoS
        long now = context.clock().now();
        if (_currentLookupPeriod + LOOKUP_THROTTLE_PERIOD > now) {
            // same period, check for DoS
            if (count >= LOOKUP_THROTTLE_MAX) {
                context.statManager().addRateData("router.throttleNetDbDoSSend", count, 0);
                return count;
            } else {
                // no DoS, at least, not yet
                return 0;
            }
        } else {
            // on to the next period, reset counter, no DoS
            // (no, I'm not worried about concurrency here)
            _currentLookupPeriod = now;
            _currentLookupCount = 1;
            return 0;
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
    
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        
        byte keyData[] = new byte[Hash.HASH_LENGTH];
        System.arraycopy(data, curIndex, keyData, 0, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        _key = new Hash(keyData);
        
        byte fromData[] = new byte[Hash.HASH_LENGTH];
        System.arraycopy(data, curIndex, fromData, 0, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        _fromHash = new Hash(fromData);
        
        boolean tunnelSpecified = false;
        switch (data[curIndex]) {
            case DataHelper.BOOLEAN_TRUE:
                tunnelSpecified = true;
                break;
            case DataHelper.BOOLEAN_FALSE:
                tunnelSpecified = false;
                break;
            default:
                throw new I2NPMessageException("Tunnel must be explicitly specified (or not)");
        }
        curIndex++;
        
        if (tunnelSpecified) {
            _replyTunnel = new TunnelId(DataHelper.fromLong(data, curIndex, 4));
            curIndex += 4;
        }
        
        int numPeers = (int)DataHelper.fromLong(data, curIndex, 2);
        curIndex += 2;
        
        if ( (numPeers < 0) || (numPeers >= (1<<16) ) )
            throw new I2NPMessageException("Invalid number of peers - " + numPeers);
        Set peers = new HashSet(numPeers);
        for (int i = 0; i < numPeers; i++) {
            byte peer[] = new byte[Hash.HASH_LENGTH];
            System.arraycopy(data, curIndex, peer, 0, Hash.HASH_LENGTH);
            curIndex += Hash.HASH_LENGTH;
            peers.add(new Hash(peer));
        }
        _dontIncludePeers = peers;
    }

    
    protected int calculateWrittenLength() {
        int totalLength = 0;
        totalLength += Hash.HASH_LENGTH*2; // key+fromHash
        totalLength += 1; // hasTunnel?
        if (_replyTunnel != null)
            totalLength += 4;
        totalLength += 2; // numPeers
        if (_dontIncludePeers != null) 
            totalLength += Hash.HASH_LENGTH * _dontIncludePeers.size();
        return totalLength;
    }
    
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        if (_key == null) throw new I2NPMessageException("Key being searched for not specified");
        if (_fromHash == null) throw new I2NPMessageException("From address not specified");

        System.arraycopy(_key.getData(), 0, out, curIndex, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        System.arraycopy(_fromHash.getData(), 0, out, curIndex, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        if (_replyTunnel != null) {
            out[curIndex++] = DataHelper.BOOLEAN_TRUE;
            byte id[] = DataHelper.toLong(4, _replyTunnel.getTunnelId());
            System.arraycopy(id, 0, out, curIndex, 4);
            curIndex += 4;
        } else {
            out[curIndex++] = DataHelper.BOOLEAN_FALSE;
        }
        if ( (_dontIncludePeers == null) || (_dontIncludePeers.size() <= 0) ) {
            out[curIndex++] = 0x0;
            out[curIndex++] = 0x0;
        } else {
            byte len[] = DataHelper.toLong(2, _dontIncludePeers.size());
            out[curIndex++] = len[0];
            out[curIndex++] = len[1];
            for (Iterator iter = _dontIncludePeers.iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                System.arraycopy(peer.getData(), 0, out, curIndex, Hash.HASH_LENGTH);
                curIndex += Hash.HASH_LENGTH;
            }
        }
        return curIndex;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(getSearchKey()) +
               DataHelper.hashCode(getFrom()) +
               DataHelper.hashCode(getReplyTunnel()) +
               DataHelper.hashCode(_dontIncludePeers);
    }
    
    @Override
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
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[DatabaseLookupMessage: ");
        buf.append("\n\tSearch Key: ").append(getSearchKey());
        buf.append("\n\tFrom: ").append(getFrom());
        buf.append("\n\tReply Tunnel: ").append(getReplyTunnel());
        buf.append("\n\tDont Include Peers: ");
        if (_dontIncludePeers != null)
            buf.append(_dontIncludePeers.size());
        buf.append("]");
        return buf.toString();
    }
}
