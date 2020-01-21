package net.i2p.router.crypto.ratchet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.southernstorm.noise.protocol.DHState;
import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.HKDF;
import net.i2p.crypto.TagSetHandle;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;

/**
 *  A tagset class for one direction, either inbound or outbound.
 *
 *  For outbound, uses very little memory. Tags and keys are generated on demand.
 *  See proposal 144.
 *
 *  For inbound, generates the tags in advance, maintaining minSize lookahead.
 *  Keys are generated as required.
 *
 *  Caller must synch on all methods.
 *
 *  @since 0.9.44
 */
class RatchetTagSet implements TagSetHandle {
    private final SessionTagListener _lsnr;
    private final PublicKey _remoteKey;
    private final SessionKey _key;
    private final HandshakeState _state;
    // We use object for tags because we must do indexOfValueByValue()
    private final SparseArray<RatchetSessionTag> _sessionTags;
    // We use byte[] for key to save space, because we don't need indexOfValueByValue()
    private final SparseArray<byte[]> _sessionKeys;
    private final HKDF hkdf;
    private final long _created;
    private long _date;
    private final int _id;
    private final int _originalSize;
    private final int _maxSize;
    private boolean _acked;
    private final byte[] _nextRootKey;
    private final byte[] _sesstag_ck;
    private final byte[] _sesstag_constant;
    private final byte[] _symmkey_ck;
    private final byte[] _symmkey_constant;
    private int _lastTag = -1;
    private int _lastKey = -1;

    private static final String INFO_1 = "KDFDHRatchetStep";
    private static final String INFO_2 = "TagAndKeyGenKeys";
    private static final String INFO_3 = "STInitialization";
    private static final String INFO_4 = "SessionTagKeyGen";
    private static final String INFO_5 = "SymmetricRatchet";
    private static final byte[] ZEROLEN = new byte[0];
    private static final int TAGLEN = RatchetSessionTag.LENGTH;
    private static final int MAX = 65535;

    /**
     *  Outbound NSR Tagset
     *
     *  @param date For outbound: creation time
     */
    public RatchetTagSet(HKDF hkdf, HandshakeState state, SessionKey rootKey, SessionKey data,
                         long date, int id) {
        this(hkdf, null, state, null, rootKey, data, date, id, false, 0, 0);
    }

    /**
     *  Outbound ES Tagset
     *
     *  @param date For outbound: creation time
     */
    public RatchetTagSet(HKDF hkdf, SessionKey rootKey, SessionKey data,
                         long date, int id) {
        this(hkdf, null, null, null, rootKey, data, date, id, false, 0, 0);
    }

    /**
     *  Inbound NSR Tagset
     *
     *  @param date For inbound: creation time
     */
    public RatchetTagSet(HKDF hkdf, SessionTagListener lsnr, HandshakeState state, SessionKey rootKey, SessionKey data,
                         long date, int id, int minSize, int maxSize) {
        this(hkdf, lsnr, state, null, rootKey, data, date, id, true, minSize, maxSize);
    }

    /**
     *  Inbound ES Tagset
     *
     *  @param date For inbound: creation time
     */
    public RatchetTagSet(HKDF hkdf, SessionTagListener lsnr,
                         PublicKey remoteKey, SessionKey rootKey, SessionKey data,
                         long date, int id, int minSize, int maxSize) {
        this(hkdf, lsnr, null, remoteKey, rootKey, data, date, id, true, minSize, maxSize);
    }


    /**
     *  @param date For inbound and outbound: creation time
     */
    private RatchetTagSet(HKDF hkdf, SessionTagListener lsnr, HandshakeState state,
                          PublicKey remoteKey, SessionKey rootKey, SessionKey data,
                          long date, int id, boolean isInbound, int minSize, int maxSize) {
        _lsnr = lsnr;
        _state = state;
        _remoteKey = remoteKey;
        _key = rootKey;
        _created = date;
        _date = date;
        _id = id;
        _originalSize = minSize;
        _maxSize = maxSize;
        _nextRootKey = new byte[32];
        byte[] ck = new byte[32];
        _sesstag_ck = new byte[32];
        _sesstag_constant = new byte[32];
        _symmkey_ck = new byte[32];
        _symmkey_constant = ZEROLEN;
        this.hkdf = hkdf;
        hkdf.calculate(rootKey.getData(), data.getData(), INFO_1, _nextRootKey, ck, 0);
        hkdf.calculate(ck, ZEROLEN, INFO_2, _sesstag_ck, _symmkey_ck, 0);
        hkdf.calculate(_sesstag_ck, ZEROLEN, INFO_3, _sesstag_ck, _sesstag_constant, 0);
        if (isInbound) {
            _sessionTags = new SparseArray<RatchetSessionTag>(minSize);
            if (state == null)
                _sessionKeys = new SparseArray<byte[]>(minSize);
            else
                _sessionKeys = null;
            for (int i = 0; i < minSize; i++) {
               storeNextTag();
            }
        } else {
            _sessionTags = null;
            _sessionKeys = null;
        }
    }

    public void clear() {
        if (_sessionTags != null)
            _sessionTags.clear();
        if (_sessionKeys != null)
            _sessionKeys.clear();
    }

    /**
     *  The far-end's public key.
     *  Valid for NSR and inbound ES tagsets.
     *  Returns null for outbound ES tagsets.
     */
    public PublicKey getRemoteKey() {
        if (_state != null) {
            DHState kp = _state.getRemotePublicKey();
            if (kp != null) {
                byte[] rv = new byte[32];
                kp.getPublicKey(rv, 0);
                return new PublicKey(EncType.ECIES_X25519, rv);
            }
        }
        return _remoteKey;
    }

    /**
     *  The identifier for the session.
     *  Not used for cryptographic operations after setup.
     */
    public SessionKey getAssociatedKey() {
        return _key;
    }

    /**
     *  For inbound/outbound NSR only, else null.
     *  MUST be cloned before processing NSR.
     */
    public HandshakeState getHandshakeState() {
        return _state;
    }

    /**
     *  For inbound and outbound: last used time
     */
    public long getDate() {
        return _date;
    }

    /**
     *  For inbound and outbound: last used time
     */
    public void setDate(long when) {
        _date = when;
    }

    /**
     *  For inbound and outbound: creation time
     */
    public long getCreated() {
        return _created;
    }

    /** for debugging */
    public int getOriginalSize() {
        return _originalSize;
    }

    /**
     *  unused tags generated
     *  @return 0 for outbound
     */
    public int size() {
        return _sessionTags != null ? _sessionTags.size() : 0;
    }

    /**
     *  tags remaining
     *  @return 0 - 65535
     */
    public int remaining() {
        int nextKey;
        if (_sessionTags != null) {
            // IB
            if (_sessionTags.size() <= 0)
                nextKey = 0;
            else
                nextKey = _sessionTags.keyAt(0);
        } else {
            // OB
            nextKey = _lastTag + 1;
        }
        return MAX - nextKey;
    }

    /**
     *  tags still available
     *  inbound only
     *  testing only
     */
    private List<RatchetSessionTag> getTags() {
        if (_sessionTags == null)
            return Collections.emptyList();
        int sz = _sessionTags.size();
        List<RatchetSessionTag> rv = new ArrayList<RatchetSessionTag>(sz);
        for (int i = 0; i < sz; i++) {
            rv.add(_sessionTags.valueAt(i));
        }
        return rv;
    }

    /**
     *  first tag still available, or null
     *  inbound only
     *  testing only
     */
    private RatchetSessionTag getFirstTag() {
        if (_sessionTags == null)
            throw new IllegalStateException("Outbound tagset");
        if (_sessionTags.size() <= 0)
            return null;
        return _sessionTags.valueAt(0);
    }

    /**
     *  inbound only
     *  @return associated SessionKey or null if not found.
     */
    public SessionKeyAndNonce consume(RatchetSessionTag tag) {
        if (_sessionTags == null)
            throw new IllegalStateException("Outbound tagset");
        // linear search for tag
        // == not equals
        int idx = _sessionTags.indexOfValueByValue(tag);
        if (idx < 0) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(RatchetTagSet.class);
            if (log.shouldWarn())
                log.warn("Tag not found " + Base64.encode(tag.getData()) +
                         " Remaining tags: " + getTags(), new Exception());
            return null;
        }
        _acked = true;
        int tagnum = _sessionTags.keyAt(idx);
        _sessionTags.removeAt(idx);

        // NSR
        if (_state != null) {
            addTags(tagnum);
            return new SessionKeyAndNonce(_state);
        }

        // ES
        // now get the key
        int kidx = _sessionKeys.indexOfKey(tagnum);
        if (kidx >= 0) {
            // already calculated
            byte[] rv = _sessionKeys.valueAt(kidx);
            _sessionKeys.removeAt(kidx);
            addTags(tagnum);
            return new SessionKeyAndNonce(rv, tagnum);
        } else if (tagnum > _lastKey) {
            // if there's any gaps, catch up and store
            for (int i = _lastKey + 1; i < tagnum; i++) {
                //System.out.println("Fill in key gap at " + i);
                _sessionKeys.put(i, consumeNextKey().getData());
            }
            SessionKeyAndNonce rv = consumeNextKey();
            addTags(tagnum);
            return rv;
        } else {
            // dup or some other error
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(RatchetTagSet.class);
            if (log.shouldWarn())
                log.warn("No key found for tag " + Base64.encode(tag.getData()) + " at index " + idx +
                         " tagnum = " + tagnum + " lastkey = " + _lastKey, new Exception());
            return null;
        }
    }

    private void addTags(int usedTagNumber) {
        // add as many as we need to maintain minSize from the tag used
        int remaining = _lastTag - usedTagNumber;
        int toAdd = _originalSize - remaining;
        if (toAdd > 0) {
            //System.out.println("Extending tags by " + toAdd);
            for (int i = 0; i < toAdd; i++) {
                storeNextTag();
            }
        }

        // trim if too big
        int toTrim = _sessionTags.size() - _maxSize;
        if (toTrim > 0) {
            //System.out.println("Trimming tags by " + toTrim);
            for (int i = 0; i < toTrim; i++) {
                int tagnum = _sessionTags.keyAt(i);
                int kidx = _sessionKeys.indexOfKey(tagnum);
                if (kidx >= 0)
                    _sessionKeys.removeAt(kidx);
                if (_lsnr != null)
                    _lsnr.expireTag(_sessionTags.valueAt(i), this);
            }
            _sessionTags.removeAtRange(0, toTrim);
        }
    }

    private void storeNextTag() {
        RatchetSessionTag tag = consumeNext();
        if (tag == null)
            return;
        _sessionTags.put(_lastTag, tag);
        if (_lsnr != null)
            _lsnr.addTag(tag, this);
    }

    /**
     *  For outbound only.
     *  Call before consumeNextKey();
     *
     *  @return a tag or null if we ran out
     */
    public RatchetSessionTag consumeNext() {
        if (_lastTag >= MAX)
            return null;
        byte[] tmp = new byte[32];
        hkdf.calculate(_sesstag_ck, _sesstag_constant, INFO_4, _sesstag_ck, tmp, 0);
        byte[] tag = new byte[TAGLEN];
        System.arraycopy(tmp, 0, tag, 0, TAGLEN);
        _lastTag++;
        return new RatchetSessionTag(tag);
    }

    /**
     *  For outbound only.
     *  Call after consumeNextTag();
     *
     *  @return a key and nonce, non-null
     */
    public SessionKeyAndNonce consumeNextKey() {
        // NSR
        if (_state != null)
            return new SessionKeyAndNonce(_state);
        // ES
        byte[] key = new byte[32];
        hkdf.calculate(_symmkey_ck, _symmkey_constant, INFO_5, _symmkey_ck, key, 0);
        _lastKey++;
        return new SessionKeyAndNonce(key, _lastKey);
    }

    /**
     *  For outbound only, call when we can use it.
     */
    public void setAcked() { _acked = true; }

    /**
     *  For inbound, returns true after first consume() call.
     *  For outbound, returns true after set.
     */
    public boolean getAcked() { return _acked; }

    /** for debugging */
    public int getID() {
        return _id;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        if (_sessionTags != null)
            buf.append("Inbound ");
        else
            buf.append("Outbound ");
        if (_state != null)
            buf.append("NSR ").append(_state.hashCode()).append(' ');
        else
            buf.append("ES ");
        buf.append("TagSet #").append(_id)
           .append("\nCreated:  ").append(DataHelper.formatTime(_created))
           .append("\nLast use: ").append(DataHelper.formatTime(_date));
        PublicKey pk = getRemoteKey();
        if (pk != null)
            buf.append("\nRemote Public Key: ").append(pk.toBase64());
        buf.append("\nRoot Symmetr. Key: ").append(_key.toBase64());
        int sz = size();
        buf.append("\nSize: ").append(sz)
           .append(" Orig: ").append(_originalSize)
           .append(" Max: ").append(_maxSize)
           .append(" Remaining: ").append(remaining());
        buf.append(" Acked? ").append(_acked);
        if (_sessionTags != null) {
            for (int i = 0; i < sz; i++) {
                int n = _sessionTags.keyAt(i);
                RatchetSessionTag tag = _sessionTags.valueAt(i);
                if (tag == null)
                    continue;
                buf.append("\n  " + n + '\t' + Base64.encode(tag.getData()));
                if (_sessionKeys != null) {
                    byte[] key = _sessionKeys.get(n);
                    if (key != null)
                        buf.append('\t' + Base64.encode(key));
                    else
                        buf.append("\tdeferred");
                }
            }
        }
        return buf.toString();
    }

/****
    public static void main(String[] args) {
        SessionKey k1 = new SessionKey(new byte[32]);
        SessionKey k2 = new SessionKey(new byte[32]);
        System.out.println("Send test");
        HKDF hkdf = new HKDF(I2PAppContext.getGlobalContext());
        RatchetTagSet rts = new RatchetTagSet(hkdf, k1, k2, 0, 0);
        System.out.println("TAGNUM\tTAG\t\tKEY");
        for (int i = 0; i < 20; i++) {
            RatchetSessionTag tag = rts.consumeNext();
            SessionKey key = rts.consumeNextKey();
            System.out.println(i + "\t" + Base64.encode(tag.getData()) + '\t' + Base64.encode(key.getData()));
        }
        System.out.println("Size now: " + rts.size());
        System.out.println("");
        System.out.println("Receive test in-order");
        rts = new RatchetTagSet(hkdf, null, k1, k2, 0, 0, 10, 50);
        System.out.println("Size now: " + rts.size());
        List<RatchetSessionTag> tags = rts.getTags();
        int j = 0;
        System.out.println("TAGNUM\tTAG\t\tKEY");
        for (RatchetSessionTag tag : tags) {
            SessionKey key = rts.consume(tag);
            if (key != null)
                System.out.println(j++ + "\t" + Base64.encode(tag.getData()) + '\t' + Base64.encode(key.getData()));
            else
                System.out.println(j++ + "\t" + Base64.encode(tag.getData()) + "\t NOT FOUND");
        }
        for (int i = 11; i <= 20; i++) {
            RatchetSessionTag tag = rts.getFirstTag();
            SessionKey key = rts.consume(tag);
            if (key != null)
                System.out.println(i + "\t" + Base64.encode(tag.getData()) + '\t' + Base64.encode(key.getData()));
            else
                System.out.println(i + "\t" + Base64.encode(tag.getData()) + "\t NOT FOUND");
        }
        System.out.println("Size now: " + rts.size());
        System.out.println("");
        System.out.println("Receive test out of order");
        rts = new RatchetTagSet(hkdf, null, k1, k2, 0, 0, 10, 50);
        System.out.println("Size now: " + rts.size());
        tags = rts.getTags();
        List<RatchetSessionTag> origtags = new ArrayList<RatchetSessionTag>(tags);
        Collections.shuffle(tags);
        System.out.println("TAGNUM\tTAG\t\tKEY");
        for (RatchetSessionTag tag : tags) {
            int idx = origtags.indexOf(tag);
            SessionKey key = rts.consume(tag);
            if (key != null) {
                System.out.println(idx + "\t" + Base64.encode(tag.getData()) + '\t' + Base64.encode(key.getData()));
                //System.out.println("Remaining tags: " + rts.getTags());
            } else {
                System.out.println(idx + "\t" + Base64.encode(tag.getData()) + "\t NOT FOUND");
            }
        }
        for (int i = 11; i <= 20; i++) {
            RatchetSessionTag tag = rts.getFirstTag();
            SessionKey key = rts.consume(tag);
            if (key != null)
                System.out.println(i + "\t" + Base64.encode(tag.getData()) + '\t' + Base64.encode(key.getData()));
            else
                System.out.println(i + "\t" + Base64.encode(tag.getData()) + "\t NOT FOUND");
        }
        System.out.println("Size now: " + rts.size());
        System.out.println(rts.toString());
    }
****/
}
