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
    private List _leases;
    private Signature _signature;
    private volatile Hash _currentRoutingKey;
    private volatile byte[] _routingKeyGenMod;

    /** um, no lease can last more than a year.  */
    private final static long MAX_FUTURE_EXPIRATION = 365 * 24 * 60 * 60 * 1000L;

    public LeaseSet() {
        setDestination(null);
        setEncryptionKey(null);
        setSigningKey(null);
        setSignature(null);
        setRoutingKey(null);
        _leases = new ArrayList();
        _routingKeyGenMod = null;
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

    public void addLease(Lease lease) {
        _leases.add(lease);
    }

    public void removeLease(Lease lease) {
        _leases.remove(lease);
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
        long when = -1;
        for (int i = 0; i < getLeaseCount(); i++) {
            Lease lse = getLease(i);
            if ((lse != null) && (lse.getEndDate() != null)) {
                if ((when <= 0) || (lse.getEndDate().getTime() < when)) when = lse.getEndDate().getTime();
            }
        }
        return when;
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
     * Determine whether there are currently valid leases, at least within a given
     * fudge factor 
     *
     * @param fudge milliseconds fudge factor to allow between the current time
     * @return true if there are current leases, false otherwise
     */
    public boolean isCurrent(long fudge) {
        long now = Clock.getInstance().now();
        long insane = now + MAX_FUTURE_EXPIRATION;
        int cnt = getLeaseCount();
        for (int i = 0; i < cnt; i++) {
            Lease l = getLease(i);
            if (l.getEndDate().getTime() > insane) {
                _log.warn("LeaseSet" + calculateHash() + " expires an insane amount in the future - skip it: " + l);
                return false;
            }
            // if it hasn't finished, we're current
            if (l.getEndDate().getTime() > now) {
                _log.debug("LeaseSet " + calculateHash() + " isn't exired: " + l);
                return true;
            } else if (l.getEndDate().getTime() > now - fudge) {
                _log.debug("LeaseSet " + calculateHash()
                           + " isn't quite expired, but its within the fudge factor so we'll let it slide: " + l);
                return true;
            }
        }
        return false;
    }

    private byte[] getBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
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
        return out.toByteArray();
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _destination = new Destination();
        _destination.readBytes(in);
        _encryptionKey = new PublicKey();
        _encryptionKey.readBytes(in);
        _signingKey = new SigningPublicKey();
        _signingKey.readBytes(in);
        int numLeases = (int) DataHelper.readLong(in, 1);
        //_version = DataHelper.readLong(in, 4);
        _leases.clear();
        for (int i = 0; i < numLeases; i++) {
            Lease lease = new Lease();
            lease.readBytes(in);
            _leases.add(lease);
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