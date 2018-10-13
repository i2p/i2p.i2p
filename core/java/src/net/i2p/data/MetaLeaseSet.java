package net.i2p.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.crypto.SigType;

/**
 * PRELIMINARY - Subject to change - see proposal 123
 *
 * @since 0.9.38
 */
public class MetaLeaseSet extends LeaseSet2 {

    public MetaLeaseSet() {
        super();
    }

    ///// overrides below here

    @Override
    public int getType() {
        return KEY_TYPE_META_LS2;
    }

    /**
     *  This does NOT validate the signature
     *
     *  @throws IllegalStateException if called more than once or Destination already set
     */
    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_destination != null)
            throw new IllegalStateException();
        // LS2 header
        readHeader(in);
        // Meta LS2 part
        _options = DataHelper.readProperties(in);
        int numLeases = in.read();
        //if (numLeases > MAX_META_LEASES)
        //    throw new DataFormatException("Too many leases - max is " + MAX_META_LEASES);
        for (int i = 0; i < numLeases; i++) {
            Lease lease = new MetaLease();
            lease.readBytes(in);
            // super to bypass overwrite of _expiration
            super.addLease(lease);
        }
        int numRevokes = in.read();
        //if (numLeases > MAX_META_REVOKES)
        //    throw new DataFormatException("Too many revokes - max is " + MAX_META_REVOKES);
        for (int i = 0; i < numRevokes; i++) {
            // TODO
            DataHelper.skip(in, 32);
        }
        // signature type depends on offline or not
        SigType type = isOffline() ? _transientSigningPublicKey.getType() : _destination.getSigningPublicKey().getType();
        _signature = new Signature(type);
        _signature.readBytes(in);
    }

    /**
     *  Without sig. This does NOT validate the signature
     */
    @Override
    protected void writeBytesWithoutSig(OutputStream out) throws DataFormatException, IOException {
        if (_destination == null || _encryptionKey == null)
            throw new DataFormatException("Not enough data to write out a LeaseSet");
        // LS2 header
        writeHeader(out);
        // Meta LS2 part
        if (_options != null && !_options.isEmpty()) {
            DataHelper.writeProperties(out, _options);
        } else {
            DataHelper.writeLong(out, 2, 0);
        }
        out.write(_leases.size());
        for (Lease lease : _leases) {
            lease.writeBytes(out);
        }
        // revocations
        out.write(0);
    }
    
    /**
     *  Number of bytes, NOT including signature
     */
    @Override
    public int size() {
        int rv = _destination.size()
             + 2
             + (_leases.size() * 40);
        if (isOffline())
            rv += 2 + _transientSigningPublicKey.length() + _offlineSignature.length();
        if (_options != null && !_options.isEmpty()) {
            try {
                rv += DataHelper.toProperties(_options).length;
            } catch (DataFormatException dfe) {
                throw new IllegalStateException("bad options", dfe);
            }
        } else {
            rv += 2;
        }
        return rv;
    }

    /**
     * @param lease must be a MetaLease
     * @throws IllegalArgumentException if not a MetaLease
     */
    @Override
    public void addLease(Lease lease) {
        if (!(lease instanceof MetaLease))
            throw new IllegalArgumentException();
        super.addLease(lease);
        _expires = _lastExpiration;
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof MetaLeaseSet)) return false;
        MetaLeaseSet ls = (MetaLeaseSet) object;
        return
               DataHelper.eq(_signature, ls.getSignature())
               && DataHelper.eq(_leases, ls._leases)
               && DataHelper.eq(getEncryptionKey(), ls.getEncryptionKey())
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
        buf.append("[MetaLeaseSet: ");
        buf.append("\n\tDestination: ").append(_destination);
        if (isOffline()) {
            buf.append("\n\tTransient Key: ").append(_transientSigningPublicKey);
            buf.append("\n\tTransient Expires: ").append(new java.util.Date(_transientExpires));
            buf.append("\n\tOffline Signature: ").append(_offlineSignature);
        }
        buf.append("\n\tOptions: ").append((_options != null) ? _options.size() : 0);
        buf.append("\n\tSignature: ").append(_signature);
        buf.append("\n\tPublished: ").append(new java.util.Date(_published));
        buf.append("\n\tExpires: ").append(new java.util.Date(_expires));
        buf.append("\n\tLeases: #").append(getLeaseCount());
        for (int i = 0; i < getLeaseCount(); i++)
            buf.append("\n\t\t").append(getLease(i));
        buf.append("]");
        return buf.toString();
    }
}
