package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.crypto.DSAEngine;

/**
 * Base implementation of common methods for the two data structures
 * that are stored in the netDb, i.e. LeaseSet and RouterInfo.
 * Implemented in 0.8.2 and retrofitted over LeaseSet and RouterInfo.
 *
 * This consolidates some common code and makes it easier to
 * implement the NetDB and I2NP without doing instanceof all over the place.
 *
 * DatabaseEntries have a SHA256 hash, a routing key, a timestamp, and
 * signatures.
 *
 * @author zzz
 * @since 0.8.2
 */
public abstract class DatabaseEntry extends DataStructureImpl {
    /** these are the same as in i2np's DatabaseStoreMessage */
    public final static int KEY_TYPE_ROUTERINFO = 0;
    public final static int KEY_TYPE_LEASESET = 1;

    protected volatile Signature _signature;
    protected volatile Hash _currentRoutingKey;
    protected volatile byte[] _routingKeyGenMod;

    /**
     * A common interface to the timestamp of the two subclasses.
     * Identical to getEarliestLeaseData() in LeaseSet,
     * and getPublished() in RouterInfo.
     * Note that for a LeaseSet this will be in the future,
     * and for a RouterInfo it will be in the past.
     * Either way, it's a timestamp.
     *
     * @since 0.8.2
     */
    public abstract long getDate();

    /**
     * Get the keys and the cert
     * Identical to getDestination() in LeaseSet,
     * and getIdentity() in RouterInfo.
     *
     * @return KAC or null
     * @since 0.8.2
     */
    protected abstract KeysAndCert getKeysAndCert();

    /**
     * A common interface to the Hash of the two subclasses.
     * Identical to getDestination().calculateHash() in LeaseSet,
     * and getIdentity().getHash() in RouterInfo.
     *
     * @return Hash or null
     * @since 0.8.2
     */
    public Hash getHash() {
        KeysAndCert kac = getKeysAndCert();
        if (kac == null)
            return null;
        return kac.getHash();
    }

    /**
     * Get the type of the data structure.
     * This should be faster than instanceof.
     *
     * @return KEY_TYPE_ROUTERINFO or KEY_TYPE_LEASESET
     * @since 0.8.2
     */
    public abstract int getType();

    /**
     * Returns the raw payload data, excluding the signature, to be signed by sign().
     * FIXME RouterInfo throws DFE and LeaseSet returns null
     * @return null on error ???????????????????????
     */
    protected abstract byte[] getBytes() throws DataFormatException;

    /**
     * Get the routing key for the structure using the current modifier in the RoutingKeyGenerator.
     * This only calculates a new one when necessary though (if the generator's key modifier changes)
     *
     */
    public Hash getRoutingKey() {
        RoutingKeyGenerator gen = RoutingKeyGenerator.getInstance();
        if ((gen.getModData() == null) || (_routingKeyGenMod == null)
            || (!DataHelper.eq(gen.getModData(), _routingKeyGenMod))) {
            _currentRoutingKey = gen.getRoutingKey(getHash());
            _routingKeyGenMod = gen.getModData();
        }
        return _currentRoutingKey;
    }


    public void setRoutingKey(Hash key) {
        _currentRoutingKey = key;
    }

    public boolean validateRoutingKey() {
        Hash destKey = getHash();
        Hash rk = RoutingKeyGenerator.getInstance().getRoutingKey(destKey);
        return rk.equals(getRoutingKey());
    }

    /**
     * Retrieve the proof that the identity stands behind the info here
     *
     */
    public Signature getSignature() {
        return _signature;
    }

    /**
     * Configure the proof that the entity stands behind the info here
     *
     * @throws IllegalStateException if already signed
     */
    public void setSignature(Signature signature) {
        if (_signature != null)
            throw new IllegalStateException();
        _signature = signature;
    }

    /**
     * Sign the structure using the supplied signing key
     *
     * @throws IllegalStateException if already signed
     */
    public void sign(SigningPrivateKey key) throws DataFormatException {
        if (_signature != null)
            throw new IllegalStateException();
        byte[] bytes = getBytes();
        if (bytes == null) throw new DataFormatException("Not enough data to sign");
        // now sign with the key 
        _signature = DSAEngine.getInstance().sign(bytes, key);
    }

    /**
     * Identical to getDestination().getSigningPublicKey() in LeaseSet,
     * and getIdentity().getSigningPublicKey() in RouterInfo.
     *
     * @return SPK or null
     * @since 0.8.2
     */
    protected SigningPublicKey getSigningPublicKey() {
        KeysAndCert kac = getKeysAndCert();
        if (kac == null)
            return null;
        return kac.getSigningPublicKey();
    }

    /**
     * This is the same as isValid() in RouterInfo
     * or verifySignature() in LeaseSet.
     * @return valid
     */
    protected boolean verifySignature() {
        if (_signature == null)
            return false;
        byte data[];
        try {
            data = getBytes();
        } catch (DataFormatException dfe) {
            return false;
        }
        if (data == null)
            return false;
        // if the data is non-null the SPK will be non-null
        return DSAEngine.getInstance().verifySignature(_signature, data, getSigningPublicKey());
    }
}
