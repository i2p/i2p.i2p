package net.i2p.data;

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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.crypto.DSAEngine;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * Defines the set of leases a destination currently has.
 *
 * Support encryption and decryption with a supplied key.
 * Only the gateways and tunnel IDs in the individual
 * leases are encrypted.
 *
 * WARNING:
 * Encryption is poorly designed and probably insecure.
 * Not recommended.
 *
 * Encrypted leases are not indicated as such.
 * The only way to tell a lease is encrypted is to
 * determine that the listed gateways do not exist.
 * Routers wishing to decrypt a leaseset must have the
 * desthash and key in their keyring.
 * This is required for the local router as well, since
 * the encryption is done on the client side of I2CP, the
 * router must decrypt it back again for local usage
 * (but not for transmission to the floodfills)
 *
 * Decrypted leases are only available through the getLease()
 * method, so that storage and network transmission via
 * writeBytes() will output the original encrypted
 * leases and the original leaseset signature.
 *
 * Revocation (zero leases) isn't used anywhere. In addition:
 *  - A revoked leaseset has an EarliestLeaseDate of -1, so it will
 *    never be stored successfully.
 *  - Revocation of an encrypted leaseset will explode.
 *  - So having an included signature at all is pointless?
 *
 *
 * @author jrandom
 */
public class LeaseSet extends DatabaseEntry {
    private Destination _destination;
    private PublicKey _encryptionKey;
    private SigningPublicKey _signingKey;
    // Keep leases in the order received, or else signature verification will fail!
    private final List<Lease> _leases;
    private boolean _receivedAsPublished;
    private boolean _receivedAsReply;
    // Store these since isCurrent() and getEarliestLeaseDate() are called frequently
    private long _firstExpiration;
    private long _lastExpiration;
    private List<Lease> _decryptedLeases;
    private boolean _decrypted;
    private boolean _checked;
    // cached byte version
    private volatile byte _byteified[];

    /**
     *  Unlimited before 0.6.3;
     *  6 as of 0.6.3;
     *  Increased in version 0.9.
     *
     *  Leasesets larger than 6 should be used with caution,
     *  as each lease adds 44 bytes, and routers older than version 0.9
     *  will not be able to connect as they will throw an exception in
     *  readBytes(). Also, the churn will be quite rapid, leading to
     *  frequent netdb stores and transmission on existing connections.
     *
     *  However we increase it now in case some hugely popular eepsite arrives.
     *  Strategies elsewhere in the router to efficiently handle
     *  large leasesets are TBD.
     */
    public static final int MAX_LEASES = 16;
    private static final int OLD_MAX_LEASES = 6;

    public LeaseSet() {
        _leases = new ArrayList<Lease>(2);
        _firstExpiration = Long.MAX_VALUE;
    }

    /**
     * Same as getEarliestLeaseDate()
     */
    public long getDate() {
        return getEarliestLeaseDate();
    }

    public KeysAndCert getKeysAndCert() {
        return _destination;
    }

    public int getType() {
        return KEY_TYPE_LEASESET;
    }

    public Destination getDestination() {
        return _destination;
    }

    /**
     * @throws IllegalStateException if already signed
     */
    public void setDestination(Destination dest) {
        if (_signature != null)
            throw new IllegalStateException();
        _destination = dest;
    }

    public PublicKey getEncryptionKey() {
        return _encryptionKey;
    }

    /**
     * @throws IllegalStateException if already signed
     */
    public void setEncryptionKey(PublicKey encryptionKey) {
        if (_signature != null)
            throw new IllegalStateException();
        _encryptionKey = encryptionKey;
    }

    /**
     *  The revocation key.
     *  @deprecated unused
     */
    @Deprecated
    public SigningPublicKey getSigningKey() {
        return _signingKey;
    }

    /**
     *  The revocation key. Unused.
     *  Must be the same type as the Destination's SigningPublicKey.
     *  @throws IllegalArgumentException if different type
     */
    public void setSigningKey(SigningPublicKey key) {
        if (key != null && _destination != null &&
            key.getType() != _destination.getSigningPublicKey().getType())
            throw new IllegalArgumentException("Signing key type mismatch");
        _signingKey = key;
    }
    
    /**
     * If true, we received this LeaseSet by a remote peer publishing it to
     * us, rather than by searching for it ourselves or locally creating it.
     * Default false.
     */
    public boolean getReceivedAsPublished() { return _receivedAsPublished; }

    /** Default false */
    public void setReceivedAsPublished(boolean received) { _receivedAsPublished = received; }

    /**
     * If true, we received this LeaseSet by searching for it
     * Default false.
     * @since 0.7.14
     */
    public boolean getReceivedAsReply() { return _receivedAsReply; }

    /** set to true @since 0.7.14 */
    public void setReceivedAsReply() { _receivedAsReply = true; }

    /**
     * @throws IllegalStateException if already signed
     */
    public void addLease(Lease lease) {
        if (lease == null) throw new IllegalArgumentException("erm, null lease");
        if (lease.getGateway() == null) throw new IllegalArgumentException("erm, lease has no gateway");
        if (lease.getTunnelId() == null) throw new IllegalArgumentException("erm, lease has no tunnel");
        if (_signature != null)
            throw new IllegalStateException();
        if (_leases.size() >= MAX_LEASES)
            throw new IllegalArgumentException("Too many leases - max is " + MAX_LEASES);
        _leases.add(lease);
        long expire = lease.getEndDate().getTime();
        if (expire < _firstExpiration)
            _firstExpiration = expire;
        if (expire > _lastExpiration)
            _lastExpiration = expire;
    }

    /**
     *  @return 0-16
     *  A LeaseSet with no leases is revoked.
     */
    public int getLeaseCount() {
        if (isEncrypted())
            return _leases.size() - 1;
        else
            return _leases.size();
    }

    public Lease getLease(int index) {
        if (isEncrypted())
            return _decryptedLeases.get(index);
        else
            return _leases.get(index);
    }

    /**
     * Retrieve the end date of the earliest lease included in this leaseSet.
     * This is the date that should be used in comparisons for leaseSet age - to
     * determine which LeaseSet was published more recently (later earliestLeaseSetDate
     * means it was published later)
     *
     * @return earliest end date of any lease in the set, or -1 if there are no leases
     */
    public long getEarliestLeaseDate() {
        if (_leases.isEmpty())
            return -1;
        return _firstExpiration;
    }

    /**
     * Retrieve the end date of the latest lease included in this leaseSet.
     * This is the date used in isCurrent().
     *
     * @return latest end date of any lease in the set, or 0 if there are no leases
     * @since 0.9.7
     */
    public long getLatestLeaseDate() {
        return _lastExpiration;
    }

    /**
     * Verify that the signature matches the lease set's destination's signing public key.
     * OR the included revocation key.
     *
     * @return true only if the signature matches
     */
    @Override
    public boolean verifySignature() {
        if (super.verifySignature())
            return true;

        // Revocation unused (see above)
        boolean signedByRevoker = DSAEngine.getInstance().verifySignature(_signature, getBytes(), _signingKey);
        return signedByRevoker;
    }

    /**
     * Verify that the signature matches the lease set's destination's signing public key.
     * OR the specified revocation key.
     *
     * @deprecated revocation unused
     * @return true only if the signature matches
     */
    @Deprecated
    public boolean verifySignature(SigningPublicKey signingKey) {
        if (super.verifySignature())
            return true;

        // Revocation unused (see above)
        boolean signedByRevoker = DSAEngine.getInstance().verifySignature(_signature, getBytes(), signingKey);
        return signedByRevoker;
    }

    /**
     * Determine whether ANY lease is currently valid, at least within a given
     * fudge factor 
     *
     * @param fudge milliseconds fudge factor to allow between the current time
     * @return true if there are current leases, false otherwise
     */
    public boolean isCurrent(long fudge) {
        long now = Clock.getInstance().now();
        return _lastExpiration > now - fudge;
    }

    protected byte[] getBytes() {
        if (_byteified != null) return _byteified;
        if ((_destination == null) || (_encryptionKey == null) || (_signingKey == null))
            return null;
        int len = _destination.size()
                + PublicKey.KEYSIZE_BYTES // encryptionKey
                + _signingKey.length() // signingKey
                + 1
                + _leases.size() * 44; // leases
        ByteArrayOutputStream out = new ByteArrayOutputStream(len);
        try {
            _destination.writeBytes(out);
            _encryptionKey.writeBytes(out);
            _signingKey.writeBytes(out);
            out.write((byte) _leases.size());
            for (Lease lease : _leases)
                lease.writeBytes(out);
        } catch (IOException ioe) {
            return null;
        } catch (DataFormatException dfe) {
            return null;
        }
        byte rv[] = out.toByteArray();
        // if we are floodfill and this was published to us
        if (_receivedAsPublished)
            _byteified = rv;
        return rv;
    }
    
    /**
     *  This does NOT validate the signature
     *
     *  @throws IllegalStateException if called more than once or Destination already set
     */
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_destination != null)
            throw new IllegalStateException();
        _destination = Destination.create(in);
        _encryptionKey = PublicKey.create(in);
        // revocation signing key must be same type as the destination signing key
        _signingKey = new SigningPublicKey(_destination.getSigningPublicKey().getType());
        _signingKey.readBytes(in);
        int numLeases = (int) DataHelper.readLong(in, 1);
        if (numLeases > MAX_LEASES)
            throw new DataFormatException("Too many leases - max is " + MAX_LEASES);
        //_version = DataHelper.readLong(in, 4);
        for (int i = 0; i < numLeases; i++) {
            Lease lease = new Lease();
            lease.readBytes(in);
            addLease(lease);
        }
        // signature must be same type as the destination signing key
        _signature = new Signature(_destination.getSigningPublicKey().getType());
        _signature.readBytes(in);
    }
    
    /**
     *  This does NOT validate the signature
     */
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_destination == null) || (_encryptionKey == null) || (_signingKey == null)
            || (_signature == null)) throw new DataFormatException("Not enough data to write out a LeaseSet");

        _destination.writeBytes(out);
        _encryptionKey.writeBytes(out);
        _signingKey.writeBytes(out);
        out.write((byte) _leases.size());
        for (Lease lease : _leases)
            lease.writeBytes(out);
        _signature.writeBytes(out);
    }
    
    /**
     *  Number of bytes, NOT including signature
     */
    public int size() {
        return _destination.size()
             + PublicKey.KEYSIZE_BYTES // encryptionKey
             + _signingKey.length() // signingKey
             + 1 // number of leases
             + _leases.size() * (Hash.HASH_LENGTH + 4 + 8);
    }
    
    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof LeaseSet)) return false;
        LeaseSet ls = (LeaseSet) object;
        return
               DataHelper.eq(_signature, ls.getSignature())
               && DataHelper.eq(_leases, ls._leases)
               && DataHelper.eq(getEncryptionKey(), ls.getEncryptionKey())
               && DataHelper.eq(_signingKey, ls.getSigningKey())
               && DataHelper.eq(_destination, ls.getDestination());

    }
    
    /** the destination has enough randomness in it to use it by itself for speed */
    @Override
    public int hashCode() {
        if (_destination == null)
            return 0;
        return _destination.hashCode();
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("[LeaseSet: ");
        buf.append("\n\tDestination: ").append(_destination);
        buf.append("\n\tEncryptionKey: ").append(_encryptionKey);
        buf.append("\n\tSigningKey: ").append(_signingKey);
        //buf.append("\n\tVersion: ").append(getVersion());
        buf.append("\n\tSignature: ").append(_signature);
        buf.append("\n\tLeases: #").append(getLeaseCount());
        for (int i = 0; i < getLeaseCount(); i++)
            buf.append("\n\t\t").append(getLease(i));
        buf.append("]");
        return buf.toString();
    }

    private static final int DATA_LEN = Hash.HASH_LENGTH + 4;
    private static final int IV_LEN = 16;

    /**
     *  Encrypt the gateway and tunnel ID of each lease, leaving the expire dates unchanged.
     *  This adds an extra dummy lease, because AES data must be padded to 16 bytes.
     *  The fact that it is encrypted is not stored anywhere.
     *  Must be called after all the leases are in place, but before sign().
     */
    public void encrypt(SessionKey key) {
        //if (_log.shouldLog(Log.WARN))
        //    _log.warn("encrypting lease: " + _destination.calculateHash());
        try {
            encryp(key);
        } catch (DataFormatException dfe) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(LeaseSet.class);
            log.error("Error encrypting lease: " + _destination.calculateHash(), dfe);
        } catch (IOException ioe) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(LeaseSet.class);
            log.error("Error encrypting lease: " + _destination.calculateHash(), ioe);
        }
    }

    /**
     *  - Put the {Gateway Hash, TunnelID} pairs for all the leases in a buffer
     *  - Pad with random data to a multiple of 16 bytes
     *  - Use the first part of the dest's public key as an IV
     *  - Encrypt
     *  - Pad with random data to a multiple of 36 bytes
     *  - Add an extra lease
     *  - Replace the Hash and TunnelID in each Lease
     */
    private void encryp(SessionKey key) throws DataFormatException, IOException {
        int size = _leases.size();
        if (size < 1 || size > MAX_LEASES-1)
            throw new IllegalArgumentException("Bad number of leases for encryption");
        int datalen = ((DATA_LEN * size / 16) + 1) * 16;
        ByteArrayOutputStream baos = new ByteArrayOutputStream(datalen);
        for (int i = 0; i < size; i++) {
            _leases.get(i).getGateway().writeBytes(baos);
            _leases.get(i).getTunnelId().writeBytes(baos);
        }
        // pad out to multiple of 16 with random data before encryption
        int padlen = datalen - (DATA_LEN * size);
        byte[] pad = new byte[padlen];
        RandomSource.getInstance().nextBytes(pad);
        baos.write(pad);
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(_destination.getPublicKey().getData(), 0, iv, 0, IV_LEN);
        byte[] enc = new byte[DATA_LEN * (size + 1)];
        I2PAppContext.getGlobalContext().aes().encrypt(baos.toByteArray(), 0, enc, 0, key, iv, datalen);
        // pad out to multiple of 36 with random data after encryption
        // (even for 4 leases, where 36*4 is a multiple of 16, we add another, just to be consistent)
        padlen = enc.length - datalen;
        RandomSource.getInstance().nextBytes(enc, datalen, padlen);
        // add the padded lease...
        Lease padLease = new Lease();
        padLease.setEndDate(_leases.get(0).getEndDate());
        _leases.add(padLease);
        // ...and replace all the gateways and tunnel ids
        ByteArrayInputStream bais = new ByteArrayInputStream(enc);
        for (int i = 0; i < size+1; i++) {
            Hash h = new Hash();
            h.readBytes(bais);
            _leases.get(i).setGateway(h);
            TunnelId t = new TunnelId();
            t.readBytes(bais);
            _leases.get(i).setTunnelId(t);
        }
    }

    /**
     *  Decrypt the leases, except for the last one which is partially padding.
     *  Store the new decrypted leases in a backing store,
     *  and keep the original leases so that verify() still works and the
     *  encrypted leaseset can be sent on to others (via writeBytes())
     */
    private void decrypt(SessionKey key) throws DataFormatException, IOException {
        //if (_log.shouldLog(Log.WARN))
        //    _log.warn("decrypting lease: " + _destination.calculateHash());
        int size = _leases.size();
        if (size < 2)
            throw new DataFormatException("Bad number of leases decrypting " + _destination.toBase32() +
                                          " - is this destination encrypted?");
        int datalen = DATA_LEN * size;
        ByteArrayOutputStream baos = new ByteArrayOutputStream(datalen);
        for (int i = 0; i < size; i++) {
            _leases.get(i).getGateway().writeBytes(baos);
            _leases.get(i).getTunnelId().writeBytes(baos);
        }
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(_destination.getPublicKey().getData(), 0, iv, 0, IV_LEN);
        int enclen = ((DATA_LEN * (size - 1) / 16) + 1) * 16;
        byte[] enc = new byte[enclen];
        System.arraycopy(baos.toByteArray(), 0, enc, 0, enclen);
        byte[] dec = new byte[enclen];
        I2PAppContext.getGlobalContext().aes().decrypt(enc, 0, dec, 0, key, iv, enclen);
        ByteArrayInputStream bais = new ByteArrayInputStream(dec);
        _decryptedLeases = new ArrayList<Lease>(size - 1);
        for (int i = 0; i < size-1; i++) {
            Lease l = new Lease();
            Hash h = new Hash();
            h.readBytes(bais);
            l.setGateway(h);
            TunnelId t = new TunnelId();
            t.readBytes(bais);
            l.setTunnelId(t);
            l.setEndDate(_leases.get(i).getEndDate());
            _decryptedLeases.add(l);
        }
    }

    /**
     * @return true if it was encrypted, and we decrypted it successfully.
     * Decrypts on first call.
     */
    private synchronized boolean isEncrypted() {
        if (_decrypted)
           return true;
        // If the encryption key is not set yet, it can't have been encrypted yet.
        // Router-side I2CP sets the destination (but not the encryption key)
        // on an unsigned LS which is pending signature (and possibly encryption)
        // by the client, and we don't want to attempt 'decryption' on it.
        if (_checked || _encryptionKey == null || _destination == null)
           return false;
        SessionKey key = I2PAppContext.getGlobalContext().keyRing().get(_destination.calculateHash());
        if (key != null) {
            try {
                decrypt(key);
                _decrypted = true;
            } catch (DataFormatException dfe) {
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(LeaseSet.class);
                log.error("Error decrypting " + _destination.toBase32() +
                          " - is this destination encrypted?", dfe);
            } catch (IOException ioe) {
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(LeaseSet.class);
                log.error("Error decrypting " + _destination.toBase32() +
                          " - is this destination encrypted?", ioe);
            }
        }
        _checked = true;
        return _decrypted;
    }
}
