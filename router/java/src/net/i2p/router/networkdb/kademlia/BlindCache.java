package net.i2p.router.networkdb.kademlia;

import java.util.concurrent.ConcurrentHashMap;

import net.i2p.crypto.Blinding;
import net.i2p.crypto.SigType;
import net.i2p.data.BlindData;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.SigningPublicKey;
import net.i2p.router.RouterContext;

/**
 *  Cache of blinding data. See proposal 123.
 *
 *  @since 0.9.40
 */
class BlindCache {

    private final RouterContext _context;
    // unblinded key
    private final ConcurrentHashMap<SigningPublicKey, BlindData> _cache;
    // blinded key
    private final ConcurrentHashMap<SigningPublicKey, BlindData> _reverseCache;
    // dest hash
    private final ConcurrentHashMap<Hash, BlindData> _hashCache;

    /**
     *  Caller MUST call startup() to load persistent cache from disk
     */
    public BlindCache(RouterContext ctx) {
        _context = ctx;
        _cache = new ConcurrentHashMap<SigningPublicKey, BlindData>(32);
        _reverseCache = new ConcurrentHashMap<SigningPublicKey, BlindData>(32);
        _hashCache = new ConcurrentHashMap<Hash, BlindData>(32);
    }

    /**
     *  May be restarted by calling startup() again.
     */
    public synchronized void shutdown() {
        store();
        _cache.clear();
        _reverseCache.clear();
        _hashCache.clear();
    }

    public synchronized void startup() {
        load();
    }

    /**
     *  The hash to lookup for a dest.
     *  If known to be blinded, returns the current blinded hash.
     *  If not known to be blinded, returns the standard dest hash.
     *
     *  @param dest may or may not be blinded
     *  @return the unblinded or blinded hash
     */
    public Hash getHash(Destination dest) {
        Hash rv = getBlindedHash(dest);
        if (rv != null)
            return rv;
        return dest.getHash();
    }

    /**
     *  The hash to lookup for a dest hash.
     *  If known to be blinded, returns the current blinded hash.
     *  If not known to be blinded, returns h.
     *
     *  @param dest may or may not be blinded
     *  @return the blinded hash or h
     */
    public Hash getHash(Hash h) {
        BlindData bd = _hashCache.get(h);
        if (bd != null)
            return bd.getBlindedHash();
        return h;
    }

    /**
     *  The hash to lookup for a dest.
     *  If known to be blinded, returns the current blinded hash.
     *  If not known to be blinded, returns null.
     *
     *  @param dest may or may not be blinded
     *  @return the blinded hash or null if not blinded
     */
    public Hash getBlindedHash(Destination dest) {
        BlindData bd = _cache.get(dest.getSigningPublicKey());
        if (bd != null)
            return bd.getBlindedHash();
        return null;
    }

    /**
     *  The hash to lookup for a SPK known to be blinded.
     *  Default blinded type assumed.
     *  Secret assumed null.
     *
     *  @param spk known to be blinded
     *  @return the blinded hash
     *  @throws IllegalArgumentException on various errors
     */
    public Hash getBlindedHash(SigningPublicKey spk) {
        BlindData bd = _cache.get(spk);
        if (bd == null)
            bd = new BlindData(_context, spk, Blinding.getDefaultBlindedType(spk.getType()), null);
        addToCache(bd);
        return bd.getBlindedHash();
    }

    /**
     *  Mark a destination as known to be blinded
     *
     *  @param dest known to be blinded
     *  @param blindedType null for default
     *  @param secret may be null
     *  @throws IllegalArgumentException on various errors
     */
    public void setBlinded(Destination dest, SigType blindedType, String secret) {
        SigningPublicKey spk = dest.getSigningPublicKey();
        BlindData bd = _cache.get(spk);
        if (bd != null) {
            bd.setDestination(dest);
        } else {
            if (blindedType == null)
                blindedType = Blinding.getDefaultBlindedType(spk.getType());
            bd = new BlindData(_context, dest, blindedType, secret);
            bd.setDestination(dest);
            addToCache(bd);
        }
    }

    /**
     *  Add the destination to the cache entry.
     *  Must previously be in cache.
     *
     *  @param dest known to be blinded
     *  @throws IllegalArgumentException on various errors
     */
    public void setBlinded(Destination dest) {
        SigningPublicKey spk = dest.getSigningPublicKey();
        BlindData bd = _cache.get(spk);
        if (bd != null) {
            bd.setDestination(dest);
            _hashCache.putIfAbsent(dest.getHash(), bd);
        }
    }

    public void addToCache(BlindData bd) {
        _cache.put(bd.getUnblindedPubKey(), bd);
        _reverseCache.put(bd.getBlindedPubKey(), bd);
        Destination dest = bd.getDestination();
        if (dest != null)
            _hashCache.put(dest.getHash(), bd);
    }

    /**
     *  The cached data or null
     */
    public BlindData getData(Destination dest) {
        BlindData rv = getData(dest.getSigningPublicKey());
        if (rv != null) {
            Destination d = rv.getDestination();
            if (d == null)
                rv.setDestination(dest);
            else if (!dest.equals(d))
                rv = null; // mismatch ???
        }
        return rv;
    }

    /**
     *  The cached data or null
     *
     *  @param spk the unblinded public key
     */
    public BlindData getData(SigningPublicKey spk) {
        SigType type = spk.getType();
        if (type != SigType.EdDSA_SHA512_Ed25519 &&
            type != SigType.RedDSA_SHA512_Ed25519)
            return null;
        return _cache.get(spk);
    }

    /**
     *  The cached data or null
     *
     *  @param spk the blinded public key
     */
    public BlindData getReverseData(SigningPublicKey spk) {
        SigType type = spk.getType();
        if (type != SigType.RedDSA_SHA512_Ed25519)
            return null;
        return _reverseCache.get(spk);
    }

    /**
     *  Refresh all the data at midnight
     *
     */
    public void rollover() {
        _reverseCache.clear();
        for (BlindData bd : _cache.values()) {
            _reverseCache.put(bd.getBlindedPubKey(), bd);
        }
    }

    private void load() {
        // TODO
    }

    private void store() {
        // TODO
    }
}
