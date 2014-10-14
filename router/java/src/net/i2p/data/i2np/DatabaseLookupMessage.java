package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.TunnelId;
//import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

/**
 * Defines the message a router sends to another router to search for a
 * key in the network database.
 *
 * @author jrandom
 */
public class DatabaseLookupMessage extends FastI2NPMessageImpl {
    //private final static Log _log = new Log(DatabaseLookupMessage.class);
    public final static int MESSAGE_TYPE = 2;
    private Hash _key;
    private Hash _fromHash;
    private TunnelId _replyTunnel;
    /** this must be kept as a list to preserve the order and not break the checksum */
    private List<Hash> _dontIncludePeers;
    private SessionKey _replyKey;
    private SessionTag _replyTag;
    private Type _type;
    
    //private static volatile long _currentLookupPeriod = 0;
    //private static volatile int _currentLookupCount = 0;
    // if we try to send over 20 netDb lookups in 10 seconds, we're acting up
    //private static final long LOOKUP_THROTTLE_PERIOD = 10*1000;
    //private static final long LOOKUP_THROTTLE_MAX = 50;

    /** Insanely big. Not much more than 1500 will fit in a message.
        Have to prevent a huge alloc on rcv of a malicious msg though */
    private static final int MAX_NUM_PEERS = 512;
    
    private static final byte FLAG_TUNNEL = 0x01;
    // any flags below here will confuse routers 0.9.5 or lower
    private static final byte FLAG_ENCRYPT = 0x02;
    private static final byte FLAG_TYPE_MASK = 0x0c;
    private static final byte FLAG_TYPE_ANY = 0;
    private static final byte FLAG_TYPE_LS = 0x04;
    private static final byte FLAG_TYPE_RI = 0x08;
    private static final byte FLAG_TYPE_EXPL = 0x0c;

    /** @since 0.9.16 */
    public enum Type {
        /** default - LS or RI */
        ANY,
        /** lease set only */
        LS,
        /** router info only */
        RI,
        /** exploratory - return closest non-floodfill router infos */
        EXPL
    }


    /**
     *  It's not supported until 0.9.7, but as of
     *  0.9.6 we can specify the bit in the flags without
     *  the receiver rejecting the whole message as invalid.
     */
    private static final String MIN_ENCRYPTION_VERSION = "0.9.7";

    public DatabaseLookupMessage(I2PAppContext context) {
        this(context, false);
    }

    /** @param locallyCreated ignored */
    public DatabaseLookupMessage(I2PAppContext context, boolean locallyCreated) {
        super(context);
        //setSearchKey(null);
        //setFrom(null);
        //setDontIncludePeers(null);
        
        // This is the wrong place for this, any throttling should be in netdb
        // And it doesnt throttle anyway (that would have to be in netdb), it just increments a stat
        //context.statManager().createRateStat("router.throttleNetDbDoSSend", "How many netDb lookup messages we are sending during a period with a DoS detected", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        //
        // only check DoS generation if we are creating the message...
        //if (locallyCreated) {
        //    // we do this in the writeMessage so we know that we have all the data
        //    int dosCount = detectDoS(context);
        //    if (dosCount > 0) {
        //        if (_log.shouldLog(Log.WARN))
        //            _log.warn("Are we flooding the network with NetDb messages?  (" + dosCount 
        //                      + " messages so far)", new Exception("Flood cause"));
        //    }
        //}
        _type = Type.ANY;
    }
    
    /**
     * Return number of netDb messages in this period, if flood, else 0
     *
     */
/*****
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
*****/
    
    /**
     * Defines the key being searched for
     */
    public Hash getSearchKey() { return _key; }

    /**
     * @throws IllegalStateException if key previously set, to protect saved checksum
     */
    public void setSearchKey(Hash key) {
        if (_key != null)
            throw new IllegalStateException();
        _key = key;
    }
    
    /**
     *  Defines the type of data being searched for.
     *  Default ANY.
     *
     *  @return non-null
     *  @since 0.9.16
     */
    public Type getSearchType() { return _type; }

    /**
     *  Defines the type of data being searched for.
     *  Default ANY.
     *  Must be ANY for queried routers 0.9.5 or lower, but there are few if
     *  any floodfills that old left, so not even worth checking.
     *
     *  @param type non-null
     *  @since 0.9.16
     */
    public void setSearchType(Type type) {
        if (type == null)
            throw new IllegalArgumentException();
        _type = type;
    }
    
    /**
     * Contains the router who requested this lookup
     *
     */
    public Hash getFrom() { return _fromHash; }
    
    /**
     * @throws IllegalStateException if from previously set, to protect saved checksum
     */
    public void setFrom(Hash from) {
        if (_fromHash != null)
            throw new IllegalStateException();
        _fromHash = from;
    }
    
    /**
     * Contains the tunnel ID a reply should be sent to
     *
     */
    public TunnelId getReplyTunnel() { return _replyTunnel; }

    /**
     * @throws IllegalStateException if tunnel previously set, to protect saved checksum
     */
    public void setReplyTunnel(TunnelId replyTunnel) {
        if (_replyTunnel != null)
            throw new IllegalStateException();
        _replyTunnel = replyTunnel;
    }
    
    /**
     *  Does this router support encrypted replies?
     *
     *  @param to null OK
     *  @since 0.9.7
     */
    public static boolean supportsEncryptedReplies(RouterInfo to) {
        if (to == null)
            return false;
        String v = to.getOption("router.version");
        return v != null &&
               VersionComparator.comp(v, MIN_ENCRYPTION_VERSION) >= 0;
    }
    
    /**
     *  The included session key or null if unset
     *
     *  @since 0.9.7
     */
    public SessionKey getReplyKey() { return _replyKey; }
    
    /**
     *  The included session tag or null if unset
     *
     *  @since 0.9.7
     */
    public SessionTag getReplyTag() { return _replyTag; }

    /**
     *  Only worthwhile if sending reply via tunnel
     *
     *  @throws IllegalStateException if key or tag previously set, to protect saved checksum
     *  @param encryptKey non-null
     *  @param encryptTag non-null
     *  @since 0.9.7
     */
    public void setReplySession(SessionKey encryptKey, SessionTag encryptTag) {
        if (_replyKey != null || _replyTag != null)
            throw new IllegalStateException();
        _replyKey = encryptKey;
        _replyTag = encryptTag;
    }
    
    /**
     * Set of peers that a lookup reply should NOT include.
     * WARNING - returns a copy.
     *
     * @return Set of Hash objects, each of which is the H(routerIdentity) to skip, or null
     */
    public Set<Hash> getDontIncludePeers() {
        if (_dontIncludePeers == null)
            return null;
        return new HashSet<Hash>(_dontIncludePeers);
    }

    /**
     * Replace the dontInclude set with this set.
     * WARNING - makes a copy.
     * Invalidates the checksum.
     *
     * @param peers may be null
     */
    public void setDontIncludePeers(Collection<Hash> peers) {
        _hasChecksum = false;
        if (peers != null)
            _dontIncludePeers = new ArrayList<Hash>(peers);
        else
            _dontIncludePeers = null;
    }

    /**
     * Add to the set.
     * Invalidates the checksum.
     *
     * @param peer non-null
     * @since 0.8.12
     */
    public void addDontIncludePeer(Hash peer) {
        if (_dontIncludePeers == null)
            _dontIncludePeers = new ArrayList<Hash>();
        else if (_dontIncludePeers.contains(peer))
            return;
        _hasChecksum = false;
        _dontIncludePeers.add(peer);
    }

    /**
     * Add to the set.
     * Invalidates the checksum.
     *
     * @param peers non-null
     * @since 0.8.12
     */
    public void addDontIncludePeers(Collection<Hash> peers) {
        _hasChecksum = false;
        if (_dontIncludePeers == null) {
            _dontIncludePeers = new ArrayList<Hash>(peers);
        } else {
            for (Hash peer : peers) {
                if (!_dontIncludePeers.contains(peer))
                    _dontIncludePeers.add(peer);
            }
        }
    }
    
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        
        //byte keyData[] = new byte[Hash.HASH_LENGTH];
        //System.arraycopy(data, curIndex, keyData, 0, Hash.HASH_LENGTH);
        _key = Hash.create(data, curIndex);
        curIndex += Hash.HASH_LENGTH;
        //_key = new Hash(keyData);
        
        //byte fromData[] = new byte[Hash.HASH_LENGTH];
        //System.arraycopy(data, curIndex, fromData, 0, Hash.HASH_LENGTH);
        _fromHash = Hash.create(data, curIndex);
        curIndex += Hash.HASH_LENGTH;
        //_fromHash = new Hash(fromData);
        
        // as of 0.9.6, ignore other 7 bits of the flag byte
        // TODO store the whole flag byte
        boolean tunnelSpecified = (data[curIndex] & FLAG_TUNNEL) != 0;
        boolean replyKeySpecified = (data[curIndex] & FLAG_ENCRYPT) != 0;
        switch (data[curIndex] & FLAG_TYPE_MASK) {
            case FLAG_TYPE_LS:
                _type = Type.LS;
                break;
            case FLAG_TYPE_RI:
                _type = Type.RI;
                break;
            case FLAG_TYPE_EXPL:
                _type = Type.EXPL;
                break;
            case FLAG_TYPE_ANY:
            default:
                _type = Type.ANY;
                break;
        }
        curIndex++;
        
        if (tunnelSpecified) {
            _replyTunnel = new TunnelId(DataHelper.fromLong(data, curIndex, 4));
            curIndex += 4;
        }
        
        int numPeers = (int)DataHelper.fromLong(data, curIndex, 2);
        curIndex += 2;
        
        if ( (numPeers < 0) || (numPeers > MAX_NUM_PEERS) )
            throw new I2NPMessageException("Invalid number of peers - " + numPeers);
        List<Hash> peers = numPeers > 0 ? new ArrayList<Hash>(numPeers) : null;
        for (int i = 0; i < numPeers; i++) {
            //byte peer[] = new byte[Hash.HASH_LENGTH];
            //System.arraycopy(data, curIndex, peer, 0, Hash.HASH_LENGTH);
            Hash p = Hash.create(data, curIndex);
            curIndex += Hash.HASH_LENGTH;
            peers.add(p);
        }
        _dontIncludePeers = peers;
        if (replyKeySpecified) {
            byte[] rk = new byte[SessionKey.KEYSIZE_BYTES];
            System.arraycopy(data, curIndex, rk, 0, SessionKey.KEYSIZE_BYTES);
            _replyKey = new SessionKey(rk);
            curIndex += SessionKey.KEYSIZE_BYTES;
            // number of tags, assume always 1 for now
            curIndex++;
            byte[] rt = new byte[SessionTag.BYTE_LENGTH];
            System.arraycopy(data, curIndex, rt, 0, SessionTag.BYTE_LENGTH);
            _replyTag = new SessionTag(rt);
        }
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
        if (_replyKey != null)
            // number of tags, assume always 1 for now
            totalLength += SessionKey.KEYSIZE_BYTES + 1 + SessionTag.BYTE_LENGTH;
        return totalLength;
    }
    
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        if (_key == null) throw new I2NPMessageException("Key being searched for not specified");
        if (_fromHash == null) throw new I2NPMessageException("From address not specified");

        System.arraycopy(_key.getData(), 0, out, curIndex, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        System.arraycopy(_fromHash.getData(), 0, out, curIndex, Hash.HASH_LENGTH);
        curIndex += Hash.HASH_LENGTH;
        // Generate the flag byte
        if (_replyTunnel != null) {
            byte flag = FLAG_TUNNEL;
            if (_replyKey != null)
                flag |= FLAG_ENCRYPT;
            switch (_type) {
                case LS:
                    flag |= FLAG_TYPE_LS;
                    break;
                case RI:
                    flag |= FLAG_TYPE_RI;
                    break;
                case EXPL:
                    flag |= FLAG_TYPE_EXPL;
                    break;
                case ANY:
                default:
                    // flag is 0
                    break;
            }
            out[curIndex++] = flag;
            byte id[] = DataHelper.toLong(4, _replyTunnel.getTunnelId());
            System.arraycopy(id, 0, out, curIndex, 4);
            curIndex += 4;
        } else if (_replyKey != null) {
            out[curIndex++] = FLAG_ENCRYPT;
        } else {
            out[curIndex++] = 0x00;
        }
        if ( (_dontIncludePeers == null) || (_dontIncludePeers.isEmpty()) ) {
            out[curIndex++] = 0x0;
            out[curIndex++] = 0x0;
        } else {
            int size = _dontIncludePeers.size();
            if (size > MAX_NUM_PEERS)
                throw new I2NPMessageException("Too many peers: " + size);
            byte len[] = DataHelper.toLong(2, size);
            out[curIndex++] = len[0];
            out[curIndex++] = len[1];
            for (Hash peer : _dontIncludePeers) {
                System.arraycopy(peer.getData(), 0, out, curIndex, Hash.HASH_LENGTH);
                curIndex += Hash.HASH_LENGTH;
            }
        }
        if (_replyKey != null) {
            System.arraycopy(_replyKey.getData(), 0, out, curIndex, SessionKey.KEYSIZE_BYTES);
            curIndex += SessionKey.KEYSIZE_BYTES;
            // number of tags, always 1 for now
            out[curIndex++] = 1;
            System.arraycopy(_replyTag.getData(), 0, out, curIndex, SessionTag.BYTE_LENGTH);
            curIndex += SessionTag.BYTE_LENGTH;
        }
        return curIndex;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_key) +
               DataHelper.hashCode(_fromHash) +
               DataHelper.hashCode(_replyTunnel) +
               DataHelper.hashCode(_dontIncludePeers);
    }
    
    @Override
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DatabaseLookupMessage) ) {
            DatabaseLookupMessage msg = (DatabaseLookupMessage)object;
            return DataHelper.eq(_key, msg._key) &&
                   DataHelper.eq(_fromHash, msg._fromHash) &&
                   DataHelper.eq(_replyTunnel, msg._replyTunnel) &&
                   DataHelper.eq(_dontIncludePeers, msg._dontIncludePeers);
        } else {
            return false;
        }
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("[DatabaseLookupMessage: ");
        buf.append("\n\tSearch Type: ").append(_type);
        buf.append("\n\tSearch Key: ").append(_key);
        if (_replyKey != null)
            buf.append("\n\tReply GW: ");
        else
            buf.append("\n\tFrom: ");
        buf.append(_fromHash);
        buf.append("\n\tReply Tunnel: ").append(_replyTunnel);
        if (_replyKey != null)
            buf.append("\n\tReply Key: ").append(_replyKey);
        if (_replyTag != null)
            buf.append("\n\tReply Tag: ").append(_replyTag);
        if (_dontIncludePeers != null) {
            buf.append("\n\tDon't Include Peers: ");
            buf.append(_dontIncludePeers.size());
        }
        buf.append("]");
        return buf.toString();
    }
}
