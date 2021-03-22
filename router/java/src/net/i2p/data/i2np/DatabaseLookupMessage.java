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
import net.i2p.crypto.EncType;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.TunnelId;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.crypto.ratchet.RatchetSessionTag;
import net.i2p.util.VersionComparator;

/**
 * Defines the message a router sends to another router to search for a
 * key in the network database.
 *
 * @author jrandom
 */
public class DatabaseLookupMessage extends FastI2NPMessageImpl {
    public final static int MESSAGE_TYPE = 2;
    private Hash _key;
    private Hash _fromHash;
    private TunnelId _replyTunnel;
    /** this must be kept as a list to preserve the order and not break the checksum */
    private List<Hash> _dontIncludePeers;
    private SessionKey _replyKey;
    private SessionTag _replyTag;
    private RatchetSessionTag _ratchetReplyTag;
    private PublicKey _ratchetPubKey;
    private Type _type;
    
    public static final boolean USE_ECIES_FF = true;

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
    // 0.9.46 or higher
    private static final byte FLAG_RATCHET = 0x10;

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
    private static final String MIN_RATCHET_VERSION = "0.9.46";

    public DatabaseLookupMessage(I2PAppContext context) {
        this(context, false);
    }

    /** @param locallyCreated ignored */
    public DatabaseLookupMessage(I2PAppContext context, boolean locallyCreated) {
        super(context);
        _type = Type.ANY;
    }
    
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
        String v = to.getVersion();
        if (VersionComparator.comp(v, MIN_ENCRYPTION_VERSION) < 0)
            return false;
        RouterIdentity ident = to.getIdentity();
        EncType type = ident.getPublicKey().getType();
        if (USE_ECIES_FF)
            return LeaseSetKeys.SET_BOTH.contains(type);
        return type == EncType.ELGAMAL_2048;
    }
    
    /**
     *  Does this router support ratchet replies?
     *
     *  @param to null OK
     *  @since 0.9.46
     */
    public static boolean supportsRatchetReplies(RouterInfo to) {
        if (to == null)
            return false;
        String v = to.getVersion();
        if (VersionComparator.comp(v, MIN_RATCHET_VERSION) < 0)
            return false;
        RouterIdentity ident = to.getIdentity();
        EncType type = ident.getPublicKey().getType();
        if (USE_ECIES_FF)
            return LeaseSetKeys.SET_BOTH.contains(type);
        return type == EncType.ELGAMAL_2048;
    }
    
    /**
     *  The included session key or null if unset.
     *  If non-null, either getReplyTag() or getRatchetReplyTag() is non-null.
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
        if (_replyKey != null || _replyTag != null ||
            _ratchetReplyTag != null || _ratchetPubKey != null)
            throw new IllegalStateException();
        _replyKey = encryptKey;
        _replyTag = encryptTag;
    }
    
    /**
     *  The included session tag or null if unset
     *
     *  @since 0.9.46
     */
    public RatchetSessionTag getRatchetReplyTag() { return _ratchetReplyTag; }

    /**
     *  Ratchet
     *
     *  @throws IllegalStateException if key or tag previously set, to protect saved checksum
     *  @param encryptKey non-null
     *  @param encryptTag non-null
     *  @since 0.9.46
     */
    public void setReplySession(SessionKey encryptKey, RatchetSessionTag encryptTag) {
        if (_replyKey != null || _replyTag != null ||
            _ratchetReplyTag != null || _ratchetPubKey != null)
            throw new IllegalStateException();
        _replyKey = encryptKey;
        _ratchetReplyTag = encryptTag;
    }
    
    /**
     *  The included session key or null if unset.
     *  Preliminary, not fully supported, see proposal 154.
     *
     *  @since 0.9.46
     */
    public PublicKey getRatchetPublicKey() { return _ratchetPubKey; }

    /**
     *  Ratchet.
     *  Preliminary, not fully supported, see proposal 154.
     *
     *  @throws IllegalStateException if key or tag previously set, to protect saved checksum
     *  @param pubKey non-null
     *  @since 0.9.46
     */
    public void setReplySession(PublicKey pubKey) {
        _ratchetPubKey = pubKey;
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
        boolean ratchetSpecified = (data[curIndex] & FLAG_RATCHET) != 0;
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
        if (replyKeySpecified || ratchetSpecified) {
            // all 3 flavors are 32 bytes
            byte[] rk = new byte[SessionKey.KEYSIZE_BYTES];
            System.arraycopy(data, curIndex, rk, 0, SessionKey.KEYSIZE_BYTES);
            if (replyKeySpecified && ratchetSpecified)
                _ratchetPubKey = new PublicKey(EncType.ECIES_X25519, rk);
            else
                _replyKey = new SessionKey(rk);
            curIndex += SessionKey.KEYSIZE_BYTES;
            if (!(replyKeySpecified && ratchetSpecified)) {
                // number of tags, assume always 1 for now
                curIndex++;
                if (replyKeySpecified) {
                    byte[] rt = new byte[SessionTag.BYTE_LENGTH];
                    System.arraycopy(data, curIndex, rt, 0, SessionTag.BYTE_LENGTH);
                    _replyTag = new SessionTag(rt);
                } else {
                    byte[] rt = new byte[RatchetSessionTag.LENGTH];
                    System.arraycopy(data, curIndex, rt, 0, RatchetSessionTag.LENGTH);
                    _ratchetReplyTag = new RatchetSessionTag(rt);
                }
            }
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
        if (_replyKey != null) {
            // number of tags, assume always 1 for now
            totalLength += SessionKey.KEYSIZE_BYTES + 1;
            if (_ratchetReplyTag != null)
                totalLength += RatchetSessionTag.LENGTH;
            else
                totalLength += SessionTag.BYTE_LENGTH;
        } else if (_ratchetPubKey != null) {
            totalLength += 32;
            // no tags
        }
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
        byte flag;
        if (_replyTag != null)
            flag = FLAG_ENCRYPT;
        else if (_ratchetReplyTag != null)
            flag = FLAG_RATCHET;
        else if (_ratchetPubKey != null)
            flag = FLAG_RATCHET | FLAG_ENCRYPT;
        else
            flag = 0;
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
                // lookup type bits are 0
                break;
        }
        if (_replyTunnel != null) {
            flag |= FLAG_TUNNEL;
            out[curIndex++] = flag;
            DataHelper.toLong(out, curIndex, 4, _replyTunnel.getTunnelId());
            curIndex += 4;
        } else {
            out[curIndex++] = flag;
        }
        if ( (_dontIncludePeers == null) || (_dontIncludePeers.isEmpty()) ) {
            out[curIndex++] = 0x0;
            out[curIndex++] = 0x0;
        } else {
            int size = _dontIncludePeers.size();
            if (size > MAX_NUM_PEERS)
                throw new I2NPMessageException("Too many peers: " + size);
            DataHelper.toLong(out, curIndex, 2, size);
            curIndex += 2;
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
            if (_replyTag != null) {
                System.arraycopy(_replyTag.getData(), 0, out, curIndex, SessionTag.BYTE_LENGTH);
                curIndex += SessionTag.BYTE_LENGTH;
            } else {
                System.arraycopy(_ratchetReplyTag.getData(), 0, out, curIndex, RatchetSessionTag.LENGTH);
                curIndex += RatchetSessionTag.LENGTH;
            }
        } else if (_ratchetPubKey != null) {
            System.arraycopy(_ratchetPubKey.getData(), 0, out, curIndex, _ratchetPubKey.length());
            curIndex += _ratchetPubKey.length();
            // no tags
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
        buf.append("\n\tSearch Key: ");
        if (_type == Type.LS)
            buf.append(_key.toBase32());
        else
            buf.append(_key);
        if (_replyTunnel != null)
            buf.append("\n\tReply GW: ");
        else
            buf.append("\n\tFrom: ");
        buf.append(_fromHash.toBase64());
        if (_replyTunnel != null)
            buf.append("\n\tReply Tunnel: ").append(_replyTunnel);
        if (_replyKey != null)
            buf.append("\n\tReply Key: ").append(_replyKey);
        if (_replyTag != null)
            buf.append("\n\tReply Tag: ").append(_replyTag);
        else if (_ratchetReplyTag != null)
            buf.append("\n\tRatchetReply Tag: ").append(_ratchetReplyTag);
        if (_dontIncludePeers != null) {
            buf.append("\n\tDon't Include Peers: ");
            buf.append(_dontIncludePeers.size());
        }
        buf.append("]");
        return buf.toString();
    }
}
