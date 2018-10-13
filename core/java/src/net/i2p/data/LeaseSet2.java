package net.i2p.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SigAlgo;
import net.i2p.crypto.SigType;
import net.i2p.util.Clock;
import net.i2p.util.OrderedProperties;

/**
 * PRELIMINARY - Subject to change - see proposal 123
 *
 * @since 0.9.38
 */
public class LeaseSet2 extends LeaseSet {
    protected int _flags;
    // stored as absolute ms
    protected long _published;
    // stored as absolute ms
    protected long _expires;
    // stored as absolute ms
    protected long _transientExpires;
    // if non-null, type of this is type of _signature in super
    protected SigningPublicKey _transientSigningPublicKey;
    // if non-null, type of this is type of SPK in the dest
    protected Signature _offlineSignature;
    // may be null
    protected Properties _options;
    // only used for unknown types; else use _encryptionKey.getType()
    private int _encType;

    private static final int FLAG_OFFLINE_KEYS = 1;
    private static final int FLAG_UNPUBLISHED = 2;

    public LeaseSet2() {
        super();
        // prevents decryption in super
        _checked = true;
    }

    public boolean isUnpublished() {
        return (_flags & FLAG_UNPUBLISHED) != 0;
    }

    public void setUnpublished() {
        _flags |= FLAG_UNPUBLISHED;
    }

    public String getOption(String opt) {
        if (_options == null)
            return null;
        return _options.getProperty(opt);
    }

    /**
     * Configure a set of options or statistics that the router can expose.
     * Makes a copy.
     *
     * Warning, clears all capabilities, must be called BEFORE addCapability().
     *
     * @param options if null, clears current options
     * @throws IllegalStateException if LeaseSet2 is already signed
     */
    public void setOptions(Properties options) {
        if (_signature != null)
            throw new IllegalStateException();
        if (_options != null)
            _options.clear();
        else
            _options = new OrderedProperties();
        if (options != null)
            _options.putAll(options);
    }

    public boolean isOffline() {
        return (_flags & FLAG_OFFLINE_KEYS) != 0;
    }

    /**
     *  Destination must be previously set.
     *
     *  @param expires absolute ms
     *  @param transientSPK the key that will sign the leaseset
     *  @param offlineSig the signature by the spk in the destination
     *  @return success, false if verify failed or expired
     */
    public boolean setOfflineSignature(long expires, SigningPublicKey transientSPK, Signature offlineSig) {
        _flags |= FLAG_OFFLINE_KEYS;
        _transientExpires = expires;
        _transientSigningPublicKey = transientSPK;
        _offlineSignature = offlineSig;
        return verifyOfflineSignature();
    }

    /**
     *  Generate a Signature to pass to setOfflineSignature()
     *
     *  @param expires absolute ms
     *  @param transientSPK the key that will sign the leaseset
     *  @param priv the private signing key for the destination
     *  @return null on error
     */
    public static Signature offlineSign(long expires, SigningPublicKey transientSPK, SigningPrivateKey priv) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        try {
            DataHelper.writeLong(baos, 4, expires / 1000);
            DataHelper.writeLong(baos, 2, transientSPK.getType().getCode());
            transientSPK.writeBytes(baos);
        } catch (IOException ioe) {
            return null;
        } catch (DataFormatException dfe) {
            return null;
        }
        byte[] data = baos.toByteArray();
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        return ctx.dsa().sign(data, priv);
    }

    public boolean verifyOfflineSignature() {
        return verifyOfflineSignature(_destination.getSigningPublicKey());
    }

    protected boolean verifyOfflineSignature(SigningPublicKey spk) {
        if (!isOffline())
            return false;
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (_transientExpires < ctx.clock().now())
            return false;
        ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        try {
            DataHelper.writeLong(baos, 4, _transientExpires / 1000);
            DataHelper.writeLong(baos, 2, _transientSigningPublicKey.getType().getCode());
            _transientSigningPublicKey.writeBytes(baos);
        } catch (IOException ioe) {
            return false;
        } catch (DataFormatException dfe) {
            return false;
        }
        byte[] data = baos.toByteArray();
        return ctx.dsa().verifySignature(_offlineSignature, data, 0, data.length, _destination.getSigningPublicKey());
    }


    ///// overrides below here


    @Override
    public int getType() {
        return KEY_TYPE_LS2;
    }

    /** without sig! */
    @Override
    protected byte[] getBytes() {
        if (_byteified != null) return _byteified;
        if (_destination == null)
            return null;
        int len = size();
        ByteArrayOutputStream out = new ByteArrayOutputStream(len);
        try {
            writeBytesWithoutSig(out);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } catch (DataFormatException dfe) {
            dfe.printStackTrace();
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
    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_destination != null)
            throw new IllegalStateException();
        // LS2 header
        readHeader(in);
        // LS2 part
        _options = DataHelper.readProperties(in);
        _encType = (int) DataHelper.readLong(in, 2);
        int encLen = (int) DataHelper.readLong(in, 2);
        // TODO
        if (_encType == 0) {
            _encryptionKey = PublicKey.create(in);
        } else {
            EncType type = EncType.getByCode(_encType);
            // type will be null if unknown
            byte[] encKey = new byte[encLen];
            DataHelper.read(in, encKey);
            // this will throw IAE if type is non-null and length is wrong
            _encryptionKey = new PublicKey(type, encKey);
        }
        int numLeases = in.read();
        if (numLeases > MAX_LEASES)
            throw new DataFormatException("Too many leases - max is " + MAX_LEASES);
        for (int i = 0; i < numLeases; i++) {
            Lease lease = new Lease2();
            lease.readBytes(in);
            // super to bypass overwrite of _expiration
            super.addLease(lease);
        }
        // signature type depends on offline or not
        SigType type = isOffline() ? _transientSigningPublicKey.getType() : _destination.getSigningPublicKey().getType();
        _signature = new Signature(type);
        _signature.readBytes(in);
    }

    /**
     *  Including sig. This does NOT validate the signature
     */
    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_signature == null)
            throw new DataFormatException("Not enough data to write out a LeaseSet");
        writeBytesWithoutSig(out);
        _signature.writeBytes(out);
    }

    /**
     *  Without sig. This does NOT validate the signature
     */
    protected void writeBytesWithoutSig(OutputStream out) throws DataFormatException, IOException {
        if (_destination == null || _encryptionKey == null)
            throw new DataFormatException("Not enough data to write out a LeaseSet");
        // LS2 header
        writeHeader(out);
        // LS2 part
        if (_options != null && !_options.isEmpty()) {
            DataHelper.writeProperties(out, _options);
        } else {
            DataHelper.writeLong(out, 2, 0);
        }
        EncType type = _encryptionKey.getType();
        if (type != null) {
            DataHelper.writeLong(out, 2, type.getCode());
        } else {
            DataHelper.writeLong(out, 2, _encType);
        }
        DataHelper.writeLong(out, 2, _encryptionKey.length());
        _encryptionKey.writeBytes(out);
        out.write((byte) _leases.size());
        for (Lease lease : _leases) {
            lease.writeBytes(out);
        }
    }
    
    protected void readHeader(InputStream in) throws DataFormatException, IOException {
        _destination = Destination.create(in);
        _published = DataHelper.readLong(in, 4) * 1000;
        _expires = _published + (DataHelper.readLong(in, 2) * 1000);
        _flags = (int) DataHelper.readLong(in, 2);
        if (isOffline())
            readOfflineBytes(in);
    }

    protected void writeHeader(OutputStream out) throws DataFormatException, IOException {
        _destination.writeBytes(out);
        if (_published <= 0)
            _published = Clock.getInstance().now();
        DataHelper.writeLong(out, 4, _published / 1000);
        DataHelper.writeLong(out, 2, (_expires - _published) / 1000);
        DataHelper.writeLong(out, 2, _flags);
        if (isOffline())
            writeOfflineBytes(out);
    }

    protected void readOfflineBytes(InputStream in) throws DataFormatException, IOException {
        _transientExpires = DataHelper.readLong(in, 4) * 1000;
        int itype = (int) DataHelper.readLong(in, 2);
        SigType type = SigType.getByCode(itype);
        if (type == null)
            throw new DataFormatException("Unknown sig type " + itype);
        _transientSigningPublicKey = new SigningPublicKey(type);
        _transientSigningPublicKey.readBytes(in);
        SigType stype = _destination.getSigningPublicKey().getType();
        _offlineSignature = new Signature(stype);
        _offlineSignature.readBytes(in);
    }

    protected void writeOfflineBytes(OutputStream out) throws DataFormatException, IOException {
        if (_transientSigningPublicKey == null || _offlineSignature == null)
            throw new DataFormatException("No offline key/sig");
        DataHelper.writeLong(out, 4, _transientExpires / 1000);
        DataHelper.writeLong(out, 2, _transientSigningPublicKey.getType().getCode());
        _transientSigningPublicKey.writeBytes(out);
        _offlineSignature.writeBytes(out);
    }
    
    /**
     *  Number of bytes, NOT including signature
     */
    @Override
    public int size() {
        int rv = _destination.size()
             + _encryptionKey.length()
             + 11
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
     * @param lease must be a Lease2
     * @throws IllegalArgumentException if not a Lease2
     */
    @Override
    public void addLease(Lease lease) {
        if (!(lease instanceof Lease2))
            throw new IllegalArgumentException();
        super.addLease(lease);
        _expires = _lastExpiration;
    }

    /**
     * Verify with the SPK in the dest for online sigs.
     * Verify with the SPK in the offline sig section for offline sigs.
     * @return valid
     */
    @Override
    public boolean verifySignature() {
        if (!isOffline())
            return super.verifySignature();
        if (_signature == null)
            return false;
        // Disallow RSA as it's so slow it could be used as a DoS
        SigType type = _signature.getType();
        if (type == null || type.getBaseAlgorithm() == SigAlgo.RSA)
            return false;
        // verify offline block
        if (!verifyOfflineSignature())
            return false;
        // verify LS2 using offline block's SPK
        // Disallow RSA as it's so slow it could be used as a DoS
        type = _transientSigningPublicKey.getType();
        if (type == null || type.getBaseAlgorithm() == SigAlgo.RSA)
            return false;
        byte data[] = getBytes();
        if (data == null)
            return false;
        return DSAEngine.getInstance().verifySignature(_signature, data, _transientSigningPublicKey);
    }
    
    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof LeaseSet2)) return false;
        LeaseSet2 ls = (LeaseSet2) object;
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
        buf.append("[LeaseSet2: ");
        buf.append("\n\tDestination: ").append(_destination);
        buf.append("\n\tEncryptionKey: ").append(_encryptionKey);
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

    @Override
    public void encrypt(SessionKey key) {
        throw new UnsupportedOperationException();
    }

/****
    public static void main(String args[]) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: LeaseSet2 privatekeyfile.dat");
            System.exit(1);
        }
        java.io.File f = new java.io.File(args[0]);
        PrivateKeyFile pkf = new PrivateKeyFile(f);
        pkf.createIfAbsent(SigType.EdDSA_SHA512_Ed25519);
        System.out.println("Online test");
        java.io.File f2 = new java.io.File("online-leaseset.dat");
        test(pkf, f2, false);
        System.out.println("Offline test");
        f2 = new java.io.File("offline-leaseset.dat");
        test(pkf, f2, true);
    }

    private static void test(PrivateKeyFile pkf, java.io.File outfile, boolean offline) throws Exception {
        net.i2p.util.RandomSource rand = net.i2p.util.RandomSource.getInstance();
        long now = System.currentTimeMillis() + 5*60*1000;
        LeaseSet2 ls2 = new LeaseSet2();
        for (int i = 0; i < 3; i++) {
            Lease2 l2 = new Lease2();
            now += 10000;
            l2.setEndDate(new java.util.Date(now));
            byte[] gw = new byte[32];
            rand.nextBytes(gw);
            l2.setGateway(new Hash(gw));
            TunnelId id = new TunnelId(1 + rand.nextLong(TunnelId.MAX_ID_VALUE));
            l2.setTunnelId(id);
            ls2.addLease(l2);
        }
        Properties opts = new Properties();
        opts.setProperty("foo", "bar");
        opts.setProperty("test", "bazzle");
        ls2.setOptions(opts);
        ls2.setDestination(pkf.getDestination());
        SimpleDataStructure encKeys[] = net.i2p.crypto.KeyGenerator.getInstance().generatePKIKeys();
        PublicKey pubKey = (PublicKey) encKeys[0];
        ls2.setEncryptionKey(pubKey);
        SigningPrivateKey spk = pkf.getSigningPrivKey();
        if (offline) {
            now += 100000;
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ls2.writeBytes(out);
        java.io.OutputStream out2 = new java.io.FileOutputStream(outfile);
        ls2.writeBytes(out2);
        out2.close();
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(out.toByteArray());
        LeaseSet2 ls3 = new LeaseSet2();
        ls3.readBytes(in);
        System.out.println("Read back: " + ls3);
        if (!ls3.verifySignature())
            System.out.println("Verify FAILED");
    }
****/
}
