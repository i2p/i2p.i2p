package net.i2p.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

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
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void setEncryptionKey(PublicKey key) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void addEncryptionKey(PublicKey key) {
        throw new UnsupportedOperationException();
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
        // null arg to get an EmptyProperties back
        _options = DataHelper.readProperties(in, null);
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
        if (_destination == null)
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
             + 10
             + (_leases.size() * MetaLease.LENGTH);
        if (isOffline())
            rv += 6 + _transientSigningPublicKey.length() + _offlineSignature.length();
        if (_options != null && !_options.isEmpty()) {
            try {
                rv += DataHelper.toProperties(_options).length;
            } catch (DataFormatException dfe) {
                throw new IllegalStateException("bad options", dfe);
            }
        } else {
            rv += 2;
        }
        // revocations TODO
        // rv += 32 * numRevocations
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
               && DataHelper.eq(_destination, ls.getDestination());
    }
    
    /** the destination has enough randomness in it to use it by itself for speed */
    @Override
    public int hashCode() {
        return super.hashCode();
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
        if (_options != null && !_options.isEmpty()) {
            for (Map.Entry<Object, Object> e : _options.entrySet()) {
                String key = (String) e.getKey();
                String val = (String) e.getValue();
                buf.append("\n\t\t[").append(key).append("] = [").append(val).append("]");
            }
        }
        buf.append("\n\tUnpublished? ").append(isUnpublished());
        buf.append("\n\tSignature: ").append(_signature);
        buf.append("\n\tPublished: ").append(new java.util.Date(_published));
        buf.append("\n\tExpires: ").append(new java.util.Date(_expires));
        buf.append("\n\tLeases: #").append(getLeaseCount());
        for (int i = 0; i < getLeaseCount(); i++)
            buf.append("\n\t\t").append(getLease(i));
        buf.append("]");
        return buf.toString();
    }

/****
    public static void main(String args[]) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: MetaLeaseSet privatekeyfile.dat");
            System.exit(1);
        }
        java.io.File f = new java.io.File(args[0]);
        PrivateKeyFile pkf = new PrivateKeyFile(f);
        pkf.createIfAbsent(SigType.EdDSA_SHA512_Ed25519);
        System.out.println("Online test");
        java.io.File f2 = new java.io.File("online-metals2.dat");
        test(pkf, f2, false);
        System.out.println("Offline test");
        f2 = new java.io.File("offline-metals2.dat");
        test(pkf, f2, true);
    }

    private static void test(PrivateKeyFile pkf, java.io.File outfile, boolean offline) throws Exception {
        net.i2p.util.RandomSource rand = net.i2p.util.RandomSource.getInstance();
        long now = System.currentTimeMillis() + 5*60*1000;
        MetaLeaseSet ls2 = new MetaLeaseSet();
        for (int i = 0; i < 3; i++) {
            MetaLease l2 = new MetaLease();
            now += 10000;
            l2.setEndDate(new java.util.Date(now));
            byte[] gw = new byte[32];
            rand.nextBytes(gw);
            l2.setGateway(new Hash(gw));
            l2.setCost(i * 5);
            l2.setType(((i & 0x01) == 0) ? KEY_TYPE_LS2 : KEY_TYPE_META_LS2);
            ls2.addLease(l2);
        }
        java.util.Properties opts = new java.util.Properties();
        //opts.setProperty("foo", "bar");
        //opts.setProperty("test", "bazzle");
        ls2.setOptions(opts);
        ls2.setDestination(pkf.getDestination());
        SigningPrivateKey spk = pkf.getSigningPrivKey();
        if (offline) {
            now += 365*24*60*60*1000L;
            SimpleDataStructure transKeys[] = net.i2p.crypto.KeyGenerator.getInstance().generateSigningKeys(SigType.EdDSA_SHA512_Ed25519);
            SigningPublicKey transientPub = (SigningPublicKey) transKeys[0];
            SigningPrivateKey transientPriv = (SigningPrivateKey) transKeys[1];
            Signature sig = offlineSign(now, transientPub, spk);
            ls2.setOfflineSignature(now, transientPub, sig);
            ls2.sign(transientPriv);
        } else {
            ls2.sign(spk);
        }
        System.out.println("Created: " + ls2);
        if (!ls2.verifySignature())
            System.out.println("Verify FAILED");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ls2.writeBytes(out);
        java.io.OutputStream out2 = new java.io.FileOutputStream(outfile);
        ls2.writeBytes(out2);
        out2.close();
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(out.toByteArray());
        System.out.println("Size calculated: " + (ls2.size() + ls2.getSignature().length()));
        System.out.println("Size to read in: " + in.available());
        LeaseSet2 ls3 = new MetaLeaseSet();
        ls3.readBytes(in);
        System.out.println("Read back: " + ls3);
        if (!ls3.verifySignature()) {
            System.out.println("Verify FAILED");
            System.out.println("Wrote out");
            byte[] b2 = ls2.toByteArray();
            System.out.println(net.i2p.util.HexDump.dump(b2));
            System.out.println("Read in");
            byte[] b3 = ls3.toByteArray();
            System.out.println(net.i2p.util.HexDump.dump(b3));
        }
    }
****/
}
