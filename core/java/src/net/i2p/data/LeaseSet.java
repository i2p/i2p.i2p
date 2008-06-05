package net.i2p.data;

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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.i2p.crypto.DSAEngine;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Defines the set of leases a destination currently has.
 *
 * @author jrandom
 */
public class LeaseSet extends DataStructureImpl {
    private final static Log _log = new Log(LeaseSet.class);
    private Destination _destination;
    private PublicKey _encryptionKey;
    private SigningPublicKey _signingKey;
    // Keep leases in the order received, or else signature verification will fail!
    private List _leases;
    private Signature _signature;
    private volatile Hash _currentRoutingKey;
    private volatile byte[] _routingKeyGenMod;
    private boolean _receivedAsPublished;
    // Store these since isCurrent() and getEarliestLeaseDate() are called frequently
    private long _firstExpiration;
    private long _lastExpiration;

    /** This seems like plenty  */
    private final static int MAX_LEASES = 6;

    public LeaseSet() {
        setDestination(null);
        setEncryptionKey(null);
        setSigningKey(null);
        setSignature(null);
        setRoutingKey(null);
        _leases = new ArrayList();
        _routingKeyGenMod = null;
        _receivedAsPublished = false;
        _firstExpiration = Long.MAX_VALUE;
        _lastExpiration = 0;
    }

    public Destination getDestination() {
        return _destination;
    }

    public void setDestination(Destination dest) {
        _destination = dest;
    }

    public PublicKey getEncryptionKey() {
        return _encryptionKey;
    }

    public void setEncryptionKey(PublicKey encryptionKey) {
        _encryptionKey = encryptionKey;
    }

    public SigningPublicKey getSigningKey() {
        return _signingKey;
    }

    public void setSigningKey(SigningPublicKey key) {
        _signingKey = key;
    }
    
    /**
     * If true, we received this LeaseSet by a remote peer publishing it to
     * us, rather than by searching for it ourselves or locally creating it.
     *
     */
    public boolean getReceivedAsPublished() { return _receivedAsPublished; }
    public void setReceivedAsPublished(boolean received) { _receivedAsPublished = received; }

    public void addLease(Lease lease) {
        if (lease == null) throw new IllegalArgumentException("erm, null lease");
        if (lease.getGateway() == null) throw new IllegalArgumentException("erm, lease has no gateway");
        if (lease.getTunnelId() == null) throw new IllegalArgumentException("erm, lease has no tunnel");
        if (_leases.size() > MAX_LEASES)
            throw new IllegalArgumentException("Too many leases - max is " + MAX_LEASES);
        _leases.add(lease);
        long expire = lease.getEndDate().getTime();
        if (expire < _firstExpiration)
            _firstExpiration = expire;
        if (expire > _lastExpiration)
            _lastExpiration = expire;
    }

    public int getLeaseCount() {
        return _leases.size();
    }

    public Lease getLease(int index) {
        return (Lease) _leases.get(index);
    }

    public Signature getSignature() {
        return _signature;
    }

    public void setSignature(Signature sig) {
        _signature = sig;
    }

    /**
     * Get the routing key for the structure using the current modifier in the RoutingKeyGenerator.
     * This only calculates a new one when necessary though (if the generator's key modifier changes)
     *
     */
    public Hash getRoutingKey() {
        RoutingKeyGenerator gen = RoutingKeyGenerator.getInstance();
        if ((gen.getModData() == null) || (_routingKeyGenMod == null)
            || (!DataHelper.eq(gen.getModData(), _routingKeyGenMod))) {
            setRoutingKey(gen.getRoutingKey(getDestination().calculateHash()));
            _routingKeyGenMod = gen.getModData();
        }
        return _currentRoutingKey;
    }

    public void setRoutingKey(Hash key) {
        _currentRoutingKey = key;
    }

    public boolean validateRoutingKey() {
        Hash destKey = getDestination().calculateHash();
        Hash rk = RoutingKeyGenerator.getInstance().getRoutingKey(destKey);
        if (rk.equals(getRoutingKey()))
            return true;

        return false;
    }

    /**
     * Retrieve the end date of the earliest lease include in this leaseSet.
     * This is the date that should be used in comparisons for leaseSet age - to
     * determine which LeaseSet was published more recently (later earliestLeaseSetDate
     * means it was published later)
     *
     * @return earliest end date of any lease in the set, or -1 if there are no leases
     */
    public long getEarliestLeaseDate() {
        if (_leases.size() <= 0)
            return -1;
        return _firstExpiration;
    }

    /**
     * Sign the structure using the supplied signing key
     *
     */
    public void sign(SigningPrivateKey key) throws DataFormatException {
        byte[] bytes = getBytes();
        if (bytes == null) throw new DataFormatException("Not enough data to sign");
        // now sign with the key 
        Signature sig = DSAEngine.getInstance().sign(bytes, key);
        setSignature(sig);
    }

    /**
     * Verify that the signature matches the lease set's destination's signing public key.
     *
     * @return true only if the signature matches
     */
    public boolean verifySignature() {
        if (getSignature() == null) return false;
        if (getDestination() == null) return false;
        byte data[] = getBytes();
        if (data == null) return false;
        boolean signedByDest = DSAEngine.getInstance().verifySignature(getSignature(), data,
                                                                       getDestination().getSigningPublicKey());
        boolean signedByRevoker = false;
        if (!signedByDest) {
            signedByRevoker = DSAEngine.getInstance().verifySignature(getSignature(), data, _signingKey);
        }
        return signedByDest || signedByRevoker;
    }

    /**
     * Verify that the signature matches the lease set's destination's signing public key.
     *
     * @return true only if the signature matches
     */
    public boolean verifySignature(SigningPublicKey signingKey) {
        if (getSignature() == null) return false;
        if (getDestination() == null) return false;
        byte data[] = getBytes();
        if (data == null) return false;
        boolean signedByDest = DSAEngine.getInstance().verifySignature(getSignature(), data,
                                                                       getDestination().getSigningPublicKey());
        boolean signedByRevoker = false;
        if (!signedByDest) {
            signedByRevoker = DSAEngine.getInstance().verifySignature(getSignature(), data, signingKey);
        }
        return signedByDest || signedByRevoker;
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

    private byte[] getBytes() {
        int len = PublicKey.KEYSIZE_BYTES  // dest
                + SigningPublicKey.KEYSIZE_BYTES // dest
                + 4 // cert
                + PublicKey.KEYSIZE_BYTES // encryptionKey
                + SigningPublicKey.KEYSIZE_BYTES // signingKey
                + 1
                + _leases.size() * 44; // leases
        ByteArrayOutputStream out = new ByteArrayOutputStream(len);
        try {
            if ((_destination == null) || (_encryptionKey == null) || (_signingKey == null) || (_leases == null))
                return null;

            _destination.writeBytes(out);
            _encryptionKey.writeBytes(out);
            _signingKey.writeBytes(out);
            DataHelper.writeLong(out, 1, _leases.size());
            //DataHelper.writeLong(out, 4, _version);
            for (Iterator iter = _leases.iterator(); iter.hasNext();) {
                Lease lease = (Lease) iter.next();
                lease.writeBytes(out);
            }
        } catch (IOException ioe) {
            return null;
        } catch (DataFormatException dfe) {
            return null;
        }
        byte rv[] = out.toByteArray();
        return rv;
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _destination = new Destination();
        _destination.readBytes(in);
        _encryptionKey = new PublicKey();
        _encryptionKey.readBytes(in);
        _signingKey = new SigningPublicKey();
        _signingKey.readBytes(in);
        int numLeases = (int) DataHelper.readLong(in, 1);
        if (numLeases > MAX_LEASES)
            throw new DataFormatException("Too many leases - max is " + MAX_LEASES);
        //_version = DataHelper.readLong(in, 4);
        _leases.clear();
        for (int i = 0; i < numLeases; i++) {
            Lease lease = new Lease();
            lease.readBytes(in);
            addLease(lease);
        }
        _signature = new Signature();
        _signature.readBytes(in);
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_destination == null) || (_encryptionKey == null) || (_signingKey == null) || (_leases == null)
            || (_signature == null)) throw new DataFormatException("Not enough data to write out a LeaseSet");

        _destination.writeBytes(out);
        _encryptionKey.writeBytes(out);
        _signingKey.writeBytes(out);
        DataHelper.writeLong(out, 1, _leases.size());
        //DataHelper.writeLong(out, 4, _version);
        for (Iterator iter = _leases.iterator(); iter.hasNext();) {
            Lease lease = (Lease) iter.next();
            lease.writeBytes(out);
        }
        _signature.writeBytes(out);
    }
    
    public int size() {
        return PublicKey.KEYSIZE_BYTES //destination.pubKey
             + SigningPublicKey.KEYSIZE_BYTES // destination.signPubKey
             + 2 // destination.certificate
             + PublicKey.KEYSIZE_BYTES // encryptionKey
             + SigningPublicKey.KEYSIZE_BYTES // signingKey
             + 1
             + _leases.size() * (Hash.HASH_LENGTH + 4 + 8);
    }

    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof LeaseSet)) return false;
        LeaseSet ls = (LeaseSet) object;
        return DataHelper.eq(getEncryptionKey(), ls.getEncryptionKey()) &&
        //DataHelper.eq(getVersion(), ls.getVersion()) &&
               DataHelper.eq(_leases, ls._leases) && DataHelper.eq(getSignature(), ls.getSignature())
               && DataHelper.eq(getSigningKey(), ls.getSigningKey())
               && DataHelper.eq(getDestination(), ls.getDestination());

    }

    public int hashCode() {
        return DataHelper.hashCode(getEncryptionKey()) +
        //(int)_version +
               DataHelper.hashCode(_leases) + DataHelper.hashCode(getSignature())
               + DataHelper.hashCode(getSigningKey()) + DataHelper.hashCode(getDestination());
    }

    public String toString() {
        StringBuffer buf = new StringBuffer(128);
        buf.append("[LeaseSet: ");
        buf.append("\n\tDestination: ").append(getDestination());
        buf.append("\n\tEncryptionKey: ").append(getEncryptionKey());
        buf.append("\n\tSigningKey: ").append(getSigningKey());
        //buf.append("\n\tVersion: ").append(getVersion());
        buf.append("\n\tSignature: ").append(getSignature());
        buf.append("\n\tLeases: #").append(getLeaseCount());
        for (int i = 0; i < getLeaseCount(); i++)
            buf.append("\n\t\tLease (").append(i).append("): ").append(getLease(i));
        buf.append("]");
        return buf.toString();
    }
}
