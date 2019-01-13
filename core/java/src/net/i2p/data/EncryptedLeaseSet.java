package net.i2p.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.crypto.SHA256Generator;
import net.i2p.crypto.SigType;
import net.i2p.util.Clock;

/**
 * Use getSigningKey() / setSigningKey() (revocation key in super) for the blinded key.
 *
 * PRELIMINARY - Subject to change - see proposal 123
 *
 * @since 0.9.38
 */
public class EncryptedLeaseSet extends LeaseSet2 {

    // includes IV and MAC
    private byte[] _encryptedData;
    private LeaseSet2 _decryptedLS2;
    private Hash __calculatedHash;

    private static final int MIN_ENCRYPTED_SIZE = 8 + 16;
    private static final int MAX_ENCRYPTED_SIZE = 4096;

    public EncryptedLeaseSet() {
        super();
    }

    ///// overrides below here

    @Override
    public int getType() {
        return KEY_TYPE_ENCRYPTED_LS2;
    }

    /**
     *  @return 0-16, or 0 if not decrypted.
     */
    @Override
    public int getLeaseCount() {
        // TODO attempt decryption
        return _decryptedLS2 != null ? _decryptedLS2.getLeaseCount() : 0;
    }

    /**
     *  @return null if not decrypted.
     */
    @Override
    public Lease getLease(int index) {
        // TODO attempt decryption
        return _decryptedLS2 != null ? _decryptedLS2.getLease(index) : null;
    }

    /**
     * Overridden to set the blinded key
     *
     * @param dest non-null, must be EdDSA_SHA512_Ed25519
     * @throws IllegalStateException if already signed
     * @throws IllegalArgumentException if not EdDSA
     */
    @Override
    public void setDestination(Destination dest) {
        super.setDestination(dest);
        SigningPublicKey spk = dest.getSigningPublicKey();
        if (spk.getType() != SigType.EdDSA_SHA512_Ed25519)
            throw new IllegalArgumentException();
        // TODO generate blinded key
        _signingKey = blind(spk, null);
    }

    private static SigningPublicKey blind(SigningPublicKey spk, SigningPrivateKey priv) {
        // TODO generate blinded key
        return spk;
    }

    /**
     * Overridden to return the blinded key so super.verifySignature() will work.
     *
     * @return SPK or null
     */
    @Override
    protected SigningPublicKey getSigningPublicKey() {
        return _signingKey;
    }

    /**
     *  This does NOT validate the signature
     *
     *  @throws IllegalStateException if called more than once or Destination already set
     */
    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_signingKey != null)
            throw new IllegalStateException();
        // LS2 header
        readHeader(in);
        // Encrypted LS2 part
        int encryptedSize = (int) DataHelper.readLong(in, 2);
        if (encryptedSize < MIN_ENCRYPTED_SIZE ||
            encryptedSize > MAX_ENCRYPTED_SIZE)
            throw new DataFormatException("bad LS size: " + encryptedSize);
        _encryptedData = new byte[encryptedSize];
        DataHelper.read(in, _encryptedData);
        // signature type depends on offline or not
        SigType type = isOffline() ? _transientSigningPublicKey.getType() : _signingKey.getType();
        _signature = new Signature(type);
        _signature.readBytes(in);
    }

    /**
     *  Without sig. This does NOT validate the signature
     */
    @Override
    protected void writeBytesWithoutSig(OutputStream out) throws DataFormatException, IOException {
        if (_signingKey == null)
            throw new DataFormatException("Not enough data to write out a LeaseSet");
        // LS2 header
        writeHeader(out);
        // Encrypted LS2 part
        if (_encryptedData == null) {
            // TODO
            encrypt(null);
        }
        DataHelper.writeLong(out, 2, _encryptedData.length);
        out.write(_encryptedData);
    }
    
    /**
     *  Overridden because we have a blinded key, not a dest
     */
    @Override
    public boolean verifyOfflineSignature() {
        return verifyOfflineSignature(_signingKey);
    }

    /**
     *  Overridden because we have a blinded key, not a dest
     */
    @Override
    protected void readHeader(InputStream in) throws DataFormatException, IOException {
        int stype = (int) DataHelper.readLong(in, 2);
        SigType type = SigType.getByCode(stype);
        if (type == null)
            throw new DataFormatException("unknown key type " + stype);
        _signingKey = new SigningPublicKey(type);
        _signingKey.readBytes(in);
        _published = DataHelper.readLong(in, 4) * 1000;
        _expires = _published + (DataHelper.readLong(in, 2) * 1000);
        _flags = (int) DataHelper.readLong(in, 2);
        if (isOffline())
            readOfflineBytes(in);
    }

    /**
     *  Overridden because we have a blinded key, not a dest
     */
    @Override
    protected void writeHeader(OutputStream out) throws DataFormatException, IOException {
        DataHelper.writeLong(out, 2, _signingKey.getType().getCode());
        _signingKey.writeBytes(out);
        if (_published <= 0)
            _published = Clock.getInstance().now();
        DataHelper.writeLong(out, 4, _published / 1000);
        DataHelper.writeLong(out, 2, (_expires - _published) / 1000);
        DataHelper.writeLong(out, 2, _flags);
        if (isOffline())
            writeOfflineBytes(out);
    }

    /**
     *  Overridden because we have a blinded key, not a dest
     */
    @Override
    protected void readOfflineBytes(InputStream in) throws DataFormatException, IOException {
        _transientExpires = DataHelper.readLong(in, 4) * 1000;
        int itype = (int) DataHelper.readLong(in, 2);
        SigType type = SigType.getByCode(itype);
        if (type == null)
            throw new DataFormatException("Unknown sig type " + itype);
        _transientSigningPublicKey = new SigningPublicKey(type);
        _transientSigningPublicKey.readBytes(in);
        SigType stype = _signingKey.getType();
        _offlineSignature = new Signature(stype);
        _offlineSignature.readBytes(in);
    }

    /**
     *  Overridden because we have a blinded key, not a dest
     */
    @Override
    protected void writeOfflineBytes(OutputStream out) throws DataFormatException, IOException {
        if (_transientSigningPublicKey == null || _offlineSignature == null)
            throw new DataFormatException("No offline key/sig");
        DataHelper.writeLong(out, 4, _transientExpires / 1000);
        DataHelper.writeLong(out, 2, _signingKey.getType().getCode());
        _transientSigningPublicKey.writeBytes(out);
        _offlineSignature.writeBytes(out);
    }
    
    /**
     *  Number of bytes, NOT including signature
     */
    @Override
    public int size() {
        int rv = _signingKey.length()
             + 12;
        if (_encryptedData != null)
            rv += _encryptedData.length;
        else
            rv += 99; // TODO
        if (isOffline())
            rv += 6 + _transientSigningPublicKey.length() + _offlineSignature.length();
        return rv;
    }

    /**
     *  Overridden because we have a blinded key, not a dest.
     *  This is the hash of the signing public key type and the signing public key.
     *  Throws IllegalStateException if not initialized.
     *
     *  @throws IllegalStateException
     */
    @Override
    public Hash getHash() {
        if (__calculatedHash == null) {
            if (_signingKey == null)
                throw new IllegalStateException();
            int len = _signingKey.length();
            byte[] b = new byte[2 + len];
            DataHelper.toLong(b, 0, 2, _signingKey.getType().getCode());
            System.arraycopy(_signingKey.getData(), 0, b, 2, len);
            __calculatedHash = SHA256Generator.getInstance().calculateHash(b);
        }
        return __calculatedHash;
    }

    /**
     *  Throws IllegalStateException if not initialized.
     *
     *  @param key ignored, to be fixed
     *  @throws IllegalStateException
     */
    @Override
    public void encrypt(SessionKey key) {
        if (_encryptedData != null)
            throw new IllegalStateException();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            // Middle layer - flag
            baos.write(0);
            // Inner layer - type - data covered by sig
            baos.write(KEY_TYPE_LS2);
            super.writeHeader(baos);
            writeBody(baos);
        } catch (DataFormatException dfe) {
            throw new IllegalStateException("Error encrypting LS2", dfe);
        } catch (IOException ioe) {
            throw new IllegalStateException("Error encrypting LS2", ioe);
        }

        // TODO sign and add signature
        // TODO encrypt - TESTING ONLY
        _encryptedData = baos.toByteArray();
        for (int i = 0; i < _encryptedData.length; i++) {
             _encryptedData[i] ^= 0x5a;
        }
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof EncryptedLeaseSet)) return false;
        EncryptedLeaseSet ls = (EncryptedLeaseSet) object;
        return
               DataHelper.eq(_signature, ls.getSignature())
               && DataHelper.eq(_signingKey, ls.getSigningKey());
    }
    
    /** the destination has enough randomness in it to use it by itself for speed */
    @Override
    public int hashCode() {
        if (_encryptionKey == null)
            return 0;
        return _encryptionKey.hashCode();
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("[EncryptedLeaseSet: ");
        buf.append("\n\tBlinded Key: ").append(_signingKey);
        if (isOffline()) {
            buf.append("\n\tTransient Key: ").append(_transientSigningPublicKey);
            buf.append("\n\tTransient Expires: ").append(new java.util.Date(_transientExpires));
            buf.append("\n\tOffline Signature: ").append(_offlineSignature);
        }
        buf.append("\n\tUnpublished? ").append(isUnpublished());
        buf.append("\n\tSignature: ").append(_signature);
        buf.append("\n\tPublished: ").append(new java.util.Date(_published));
        buf.append("\n\tExpires: ").append(new java.util.Date(_expires));
        buf.append("]");
        return buf.toString();
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
        java.io.File f2 = new java.io.File("online-encls2.dat");
        test(pkf, f2, false);
        System.out.println("Offline test");
        f2 = new java.io.File("offline-encls2.dat");
        test(pkf, f2, true);
    }

    private static void test(PrivateKeyFile pkf, java.io.File outfile, boolean offline) throws Exception {
        net.i2p.util.RandomSource rand = net.i2p.util.RandomSource.getInstance();
        long now = System.currentTimeMillis() + 5*60*1000;
        EncryptedLeaseSet ls2 = new EncryptedLeaseSet();
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
        java.util.Properties opts = new java.util.Properties();
        opts.setProperty("foof", "bar");
        ls2.setOptions(opts);
        ls2.setDestination(pkf.getDestination());
        SimpleDataStructure encKeys[] = net.i2p.crypto.KeyGenerator.getInstance().generatePKIKeys();
        PublicKey pubKey = (PublicKey) encKeys[0];
        ls2.addEncryptionKey(pubKey);
        net.i2p.crypto.KeyPair encKeys2 = net.i2p.crypto.KeyGenerator.getInstance().generatePKIKeys(net.i2p.crypto.EncType.ECIES_X25519);
        pubKey = encKeys2.getPublic();
        ls2.addEncryptionKey(pubKey);
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ls2.writeBytes(out);
        java.io.OutputStream out2 = new java.io.FileOutputStream(outfile);
        ls2.writeBytes(out2);
        out2.close();
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(out.toByteArray());
        System.out.println("Size calculated: " + (ls2.size() + ls2.getSignature().length()));
        System.out.println("Size to read in: " + in.available());
        EncryptedLeaseSet ls3 = new EncryptedLeaseSet();
        ls3.readBytes(in);
        System.out.println("Read back: " + ls3);
        if (!ls3.verifySignature())
            System.out.println("Verify FAILED");
    }
****/
}
