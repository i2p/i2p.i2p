package net.i2p.router.networkdb.kademlia;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.crypto.Blinding;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.BlindData;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;

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

    private static final String PERSIST_FILE = "router.blindcache.dat";

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
     *  @param h may or may not be blinded
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
    public synchronized void rollover() {
        _reverseCache.clear();
        for (BlindData bd : _cache.values()) {
            _reverseCache.put(bd.getBlindedPubKey(), bd);
        }
    }

    /**
     *  Load from file.
     *  Format:
     *  sigtype,bsigtype,b64 pubkey,[b64 secret],[b64 dest]
     */
    private synchronized void load() {
        File file = new File(_context.getConfigDir(), PERSIST_FILE);
        if (!file.exists())
            return;
        Log log = _context.logManager().getLog(BlindCache.class);
        int count = 0;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
            		new FileInputStream(file), "ISO-8859-1"));
            String line = null;
            while ( (line = br.readLine()) != null) {
                if (line.startsWith("#"))
                    continue;
                try {
                    addToCache(fromPersistentString(line));
                    count++;
                } catch (IllegalArgumentException iae) {
                    if (log.shouldLog(Log.WARN))
                        log.warn("Error reading cache entry", iae);
                } catch (DataFormatException dfe) {
                    if (log.shouldLog(Log.WARN))
                        log.warn("Error reading cache entry", dfe);
                }
            }
        } catch (IOException ioe) {
            if (log.shouldLog(Log.WARN) && file.exists())
                log.warn("Error reading the blinding cache file", ioe);
        } finally {
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
        if (log.shouldLog(Log.INFO))
            log.info("Loaded " + count + " entries from " + file);
    }

    private synchronized void store() {
        if (_cache.isEmpty())
            return;
        Log log = _context.logManager().getLog(BlindCache.class);
        int count = 0;
        File file = new File(_context.getConfigDir(), PERSIST_FILE);
        PrintWriter out = null;
        try {
            out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(file), "ISO-8859-1")));
            out.println("# Blinding cache entries. Format is: sigtype,bsigtype,authtype,time,key,[secret],[privkey],[dest]");
            for (BlindData bd : _cache.values()) {
                 out.println(toPersistentString(bd));
                 count++;
            }
            if (out.checkError())
                throw new IOException("Failed write to " + file);
        } catch (IOException ioe) {
            if (log.shouldLog(Log.WARN))
                log.warn("Error writing the blinding cache File", ioe);
        } finally {
            if (out != null) out.close();
        }
        if (log.shouldLog(Log.INFO))
            log.info("Stored " + count + " entries to " + file);
    }

    /**
     *  Format:
     *  sigtype,bsigtype,authtype,timestamp,b64 pubkey,[b64 secret],[b64 auth privkey],[b64 dest]
     */
    private BlindData fromPersistentString(String line) throws DataFormatException {
        String[] ss = DataHelper.split(line, ",", 8);
        if (ss.length != 8)
            throw new DataFormatException("bad format");
        int ist1, ist2, auth;
        long time;
        try {
            ist1 = Integer.parseInt(ss[0]);
            ist2 = Integer.parseInt(ss[1]);
            auth = Integer.parseInt(ss[2]);
            time = Long.parseLong(ss[3]);
        } catch (NumberFormatException nfe) {
            throw new DataFormatException("bad codes", nfe);
        }
        SigType st1 = SigType.getByCode(ist1);
        SigType st2 = SigType.getByCode(ist2);
        if (st1 == null || !st1.isAvailable() || st2 == null || !st2.isAvailable())
            throw new DataFormatException("bad codes");
        SigningPublicKey spk = new SigningPublicKey(st1);
        spk.fromBase64(ss[4]);
        String secret;
        if (ss[5].length() > 0) {
            byte[] b = Base64.decode(ss[5]);
            if (b == null)
                throw new DataFormatException("bad secret");
            secret = DataHelper.getUTF8(b);
        } else {
            secret = null;
        }
        PrivateKey privkey;
        if (ss[6].length() > 0) {
            byte[] b = Base64.decode(ss[6]);
            if (b == null)
                throw new DataFormatException("bad privkey");
            privkey = new PrivateKey(EncType.ECIES_X25519, b);
        } else {
            privkey = null;
        }
        BlindData rv;
        // TODO pass privkey
        if (ss[7].length() > 0) {
            Destination dest = new Destination(ss[7]);
            if (!spk.equals(dest.getSigningPublicKey()))
                throw new DataFormatException("spk mismatch");
            rv = new BlindData(_context, dest, st2, secret);
        } else {
            rv = new BlindData(_context, spk, st2, secret);
        }
        return rv;
    }

    /**
     *  Format:
     *  sigtype,bsigtype,authtype,timestamp,b64 pubkey,[b64 secret],[b64 auth privkey],[b64 dest]
     */
    private static String toPersistentString(BlindData bd) {
        StringBuilder buf = new StringBuilder(1024);
        SigningPublicKey spk = bd.getUnblindedPubKey();
        buf.append(spk.getType().getCode()).append(',');
        buf.append(bd.getBlindedSigType().getCode()).append(',');
        buf.append(bd.getAuthType()).append(',');
        // timestamp todo
        buf.append('0').append(',');
        buf.append(spk.toBase64()).append(',');
        String secret = bd.getSecret();
        if (secret != null && secret.length() > 0)
            buf.append(Base64.encode(secret));
        buf.append(',');
        PrivateKey pk = bd.getAuthPrivKey();
        if (pk != null)
            buf.append(pk.toBase64());
        buf.append(',');
        Destination dest = bd.getDestination();
        if (dest != null)
            buf.append(dest.toBase64());
        return buf.toString();
    }
}
