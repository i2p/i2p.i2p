package net.i2p.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.crypto.Blinding;
import net.i2p.crypto.ChaCha20;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.HKDF;
import net.i2p.crypto.SHA256Generator;
import net.i2p.crypto.SigType;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Use getSigningKey() / setSigningKey() (revocation key in super) for the blinded key.
 *
 * PRELIMINARY - Subject to change - see proposal 123
 *
 * Per-client auth TODO
 *
 * @since 0.9.38
 */
public class EncryptedLeaseSet extends LeaseSet2 {

    // includes salt
    private byte[] _encryptedData;
    private LeaseSet2 _decryptedLS2;
    private Hash __calculatedHash;
    private SigningPrivateKey _alpha;
    // to decrypt with if we don't have full dest
    private SigningPublicKey _unblindedSPK;
    private String _secret;
    private final Log _log;

    private static final int MIN_ENCRYPTED_SIZE = 8 + 16;
    private static final int MAX_ENCRYPTED_SIZE = 4096;

    private static final int SALT_LEN = 32;
    private static final byte[] CREDENTIAL = DataHelper.getASCII("credential");
    private static final byte[] SUBCREDENTIAL = DataHelper.getASCII("subcredential");
    private static final String ELS2L1K = "ELS2_L1K";
    private static final String ELS2L2K = "ELS2_L2K";

    public EncryptedLeaseSet() {
        super();
        _log = I2PAppContext.getGlobalContext().logManager().getLog(EncryptedLeaseSet.class);
    }

    /**
     *  @return leaseset or null if not decrypted.
     *          Also returns null if we created and encrypted it.
     *  @since 0.9.39
     */
    public LeaseSet2 getDecryptedLeaseSet() {
        return _decryptedLS2;
    }

    /**
     *  Must be set before sign or verify.
     *
     *  @param secret null or "" for none (default)
     *  @since 0.9.39
     */
    public void setSecret(String secret) {
        _secret = secret;
    }

    ///// overrides below here

    @Override
    public int getType() {
        // return type 3 before signing so inner signing works
        return (_signature != null) ? KEY_TYPE_ENCRYPTED_LS2 : KEY_TYPE_LS2;
    }

    /**
     *  @return 0-16, or 0 if not decrypted.
     */
    @Override
    public int getLeaseCount() {
        return _decryptedLS2 != null ? _decryptedLS2.getLeaseCount() : 0;
    }

    /**
     *  @return null if not decrypted.
     */
    @Override
    public Lease getLease(int index) {
        return _decryptedLS2 != null ? _decryptedLS2.getLease(index) : null;
    }

    /**
     *  @return null if not decrypted.
     *  @since 0.9.39
     */
    @Override
    public List<PublicKey> getEncryptionKeys() {
        if (_decryptedLS2 != null)
            return _decryptedLS2.getEncryptionKeys();
        return super.getEncryptionKeys();
    }

    /**
     * Overridden to set the blinded key
     *
     * @param dest non-null, must be EdDSA_SHA512_Ed25519 or RedDSA_SHA512_Ed25519
     * @throws IllegalStateException if already signed
     * @throws IllegalArgumentException if not EdDSA
     */
    @Override
    public void setDestination(Destination dest) {
        if (_signature != null && _destination != null) {
            if (!dest.equals(_destination))
                throw new IllegalStateException();
        } else {
            _destination = dest;
        }
        SigningPublicKey spk = dest.getSigningPublicKey();
        setSigningKey(spk);
    }

    /**
     * Overridden to set the blinded key
     *
     * @param spk unblinded key non-null, must be EdDSA_SHA512_Ed25519 or RedDSA_SHA512_Ed25519
     * @throws IllegalStateException if already signed
     * @throws IllegalArgumentException if not EdDSA
     * @since 0.9.40
     */
    @Override
    public void setSigningKey(SigningPublicKey spk) {
        // TODO already-set checks
        SigType type = spk.getType();
        if (type != SigType.EdDSA_SHA512_Ed25519 &&
            type != SigType.RedDSA_SHA512_Ed25519)
            throw new IllegalArgumentException();
        if (_unblindedSPK != null) {
            if (!_unblindedSPK.equals(spk))
                throw new IllegalArgumentException("unblinded pubkey mismatch");
        } else {
            _unblindedSPK = spk;
        }
        SigningPublicKey bpk = blind(spk);
        if (_signingKey == null)
            _signingKey = bpk;
        else if (!_signingKey.equals(bpk))
            throw new IllegalArgumentException("blinded pubkey mismatch:" +
                                               "\nas received:   " + _signingKey +
                                               "\nas calculated: " + bpk);
    }

    /**
     * Generate blinded pubkey from the unblinded pubkey in the destination,
     * which must have been previously set.
     *
     * @since 0.9.39
     */
    private SigningPublicKey blind(SigningPublicKey spk) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (_published <= 0)
            _alpha = Blinding.generateAlpha(ctx, spk, _secret);
        else
            _alpha = Blinding.generateAlpha(ctx, spk, _secret, _published);
        SigningPublicKey rv = Blinding.blind(spk, _alpha);
        if (_log.shouldDebug())
            _log.debug("Blind:" +
                       "\norig:    " + spk +
                       "\nalpha:   " + _alpha +
                       "\nblinded: " + rv);
        return rv;
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
     *  Before encrypt() is called, the inner leaseset.
     *  After encrypt() is called, the encrypted data.
     *  Without sig. This does NOT validate the signature
     */
    @Override
    protected void writeBytesWithoutSig(OutputStream out) throws DataFormatException, IOException {
        if (_signingKey == null)
            throw new DataFormatException("Not enough data to write out a LeaseSet");
        if (_encryptedData == null) {
            super.writeHeader(out);
            writeBody(out);
        } else {
            // for signing the inner part
            writeHeader(out);
            // After signing
            // Encrypted LS2 part
            DataHelper.writeLong(out, 2, _encryptedData.length);
            out.write(_encryptedData);
        }
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
     *  This must be used instead of getDestination().getHash().
     *
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
     *  @param skey ignored
     *  @throws IllegalStateException
     */
    @Override
    public void encrypt(SessionKey skey) {
        if (_encryptedData != null)
            throw new IllegalStateException("already encrypted");
        if (_signature == null)
            throw new IllegalStateException("not signed");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // inner LS is always unpublished
        int saveFlags = _flags;
        setUnpublished();
        try {
            // Inner layer - type - data covered by sig
            baos.write(KEY_TYPE_LS2);
            super.writeHeader(baos);
            writeBody(baos);
            _signature.writeBytes(baos);
        } catch (DataFormatException dfe) {
            throw new IllegalStateException("Error encrypting LS2", dfe);
        } catch (IOException ioe) {
            throw new IllegalStateException("Error encrypting LS2", ioe);
        } finally {
            _flags = saveFlags;
        }

        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        byte[] input = getHKDFInput(ctx);

        // layer 2 (inner) encryption
        byte[] salt = new byte[SALT_LEN];
        ctx.random().nextBytes(salt);
        HKDF hkdf = new HKDF(ctx);
        byte[] key = new byte[32];
        // use first 12 bytes only
        byte[] iv = new byte[32];
        hkdf.calculate(salt, input, ELS2L2K, key, iv, 0);
        byte[] plaintext = baos.toByteArray();
        byte[] ciphertext = new byte[1 + SALT_LEN + plaintext.length];
        // Middle layer - flag
        ciphertext[0] = 0;
        System.arraycopy(salt, 0, ciphertext, 1, SALT_LEN);
        ChaCha20.encrypt(key, iv, plaintext, 0, ciphertext, 1 + SALT_LEN, plaintext.length);
        if (_log.shouldDebug()) {
            _log.debug("Encrypt: inner plaintext:\n" + net.i2p.util.HexDump.dump(plaintext));
            _log.debug("Encrypt: inner ciphertext:\n" + net.i2p.util.HexDump.dump(ciphertext));
        }

        // layer 1 (outer) encryption
        // reuse input (because there's no authcookie), generate new salt/key/iv
        ctx.random().nextBytes(salt);
        hkdf.calculate(salt, input, ELS2L1K, key, iv, 0);
        plaintext = ciphertext;
        ciphertext = new byte[SALT_LEN + plaintext.length];
        System.arraycopy(salt, 0, ciphertext, 0, SALT_LEN);
        if (_log.shouldDebug()) {
            _log.debug("Encrypt: chacha20 key:\n" + net.i2p.util.HexDump.dump(key));
            _log.debug("Encrypt: chacha20 IV:\n" + net.i2p.util.HexDump.dump(iv));
        }
        ChaCha20.encrypt(key, iv, plaintext, 0, ciphertext, SALT_LEN, plaintext.length);
        if (_log.shouldDebug())
            _log.debug("Encrypt: outer ciphertext:\n" + net.i2p.util.HexDump.dump(ciphertext));
        _encryptedData = ciphertext;
    }

    /**
     *  Throws IllegalStateException if not initialized.
     *
     *  @param skey ignored
     *  @throws IllegalStateException
     */
    private void decrypt() throws DataFormatException, IOException {
        if (_encryptedData == null)
            throw new IllegalStateException("not encrypted");
        if (_decryptedLS2 != null)
            return;
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        byte[] input = getHKDFInput(ctx);

        // layer 1 (outer) decryption
        HKDF hkdf = new HKDF(ctx);
        byte[] key = new byte[32];
        // use first 12 bytes only
        byte[] iv = new byte[32];
        byte[] ciphertext = _encryptedData;
        byte[] plaintext = new byte[ciphertext.length - SALT_LEN];
        // first 32 bytes of ciphertext are the salt
        hkdf.calculate(ciphertext, input, ELS2L1K, key, iv, 0);
        if (_log.shouldDebug()) {
            _log.debug("Decrypt: chacha20 key:\n" + net.i2p.util.HexDump.dump(key));
            _log.debug("Decrypt: chacha20 IV:\n" + net.i2p.util.HexDump.dump(iv));
        }
        ChaCha20.decrypt(key, iv, ciphertext, SALT_LEN, plaintext, 0, plaintext.length);
        if (_log.shouldDebug()) {
            _log.debug("Decrypt: outer ciphertext:\n" + net.i2p.util.HexDump.dump(ciphertext));
            _log.debug("Decrypt: outer plaintext:\n" + net.i2p.util.HexDump.dump(plaintext));
        }

        boolean perClient = (plaintext[0] & 0x01) != 0;
        if (perClient) {
            int authScheme = (plaintext[0] & 0x0e) >> 1;
            // TODO
            throw new DataFormatException("Per client auth unsupported, scheme: " + authScheme);
        }

        // layer 2 (inner) decryption
        // reuse input (because there's no authcookie), get new salt/key/iv
        ciphertext = plaintext;
        plaintext = new byte[ciphertext.length  - (1 + SALT_LEN)];
        byte[] salt = new byte[SALT_LEN];
        System.arraycopy(ciphertext, 1, salt, 0, SALT_LEN);
        hkdf.calculate(salt, input, ELS2L2K, key, iv, 0);
        ChaCha20.decrypt(key, iv, ciphertext, 1 + SALT_LEN, plaintext, 0, plaintext.length);
        if (_log.shouldDebug())
            _log.debug("Decrypt: inner plaintext:\n" + net.i2p.util.HexDump.dump(plaintext));
        ByteArrayInputStream bais = new ByteArrayInputStream(plaintext);
        int type = bais.read();
        LeaseSet2 innerLS2;
        if (type == KEY_TYPE_LS2)
            innerLS2 = new LeaseSet2();
        else if (type == KEY_TYPE_META_LS2)
            innerLS2 = new MetaLeaseSet();
        else
            throw new DataFormatException("Unsupported LS type: " + type);
        innerLS2.readBytes(bais);
        _decryptedLS2 = innerLS2;
    }

    /**
     *  The HKDF input
     *
     *  @return 36 bytes
     *  @since 0.9.39
     */
    private byte[] getHKDFInput(I2PAppContext ctx) {
        byte[] subcredential = getSubcredential(ctx);
        byte[] rv = new byte[subcredential.length + 4];
        System.arraycopy(subcredential, 0, rv, 0, subcredential.length);
        DataHelper.toLong(rv, subcredential.length, 4, _published / 1000);
        return rv;
    }

    /**
     *  The subcredential
     *
     *  @return 32 bytes
     *  @throws IllegalStateException if we don't have it
     *  @since 0.9.39
     */
    private byte[] getSubcredential(I2PAppContext ctx) {
        if (_unblindedSPK == null)
            throw new IllegalStateException("no known SPK to decrypt with");
        int spklen = _unblindedSPK.length();
        byte[] in = new byte[spklen + 4];
        // SHA256("credential" || spk || sigtypein || sigtypeout)
        System.arraycopy(_unblindedSPK.getData(), 0, in, 0, spklen);
        DataHelper.toLong(in, spklen, 2, _unblindedSPK.getType().getCode());
        DataHelper.toLong(in, spklen + 2, 2, SigType.RedDSA_SHA512_Ed25519.getCode());
        byte[] credential = hash(ctx, CREDENTIAL, in);
        byte[] spk = _signingKey.getData();
        byte[] tmp = new byte[credential.length + spk.length];
        System.arraycopy(credential, 0, tmp, 0, credential.length);
        System.arraycopy(spk, 0, tmp, credential.length, spk.length);
        return hash(ctx, SUBCREDENTIAL, tmp);
    }

    /**
     *  Hash with a personalization string
     *
     *  @return 32 bytes
     *  @since 0.9.39
     */
    private static byte[] hash(I2PAppContext ctx, byte[] p, byte[] d) {
        byte[] data = new byte[p.length + d.length];
        System.arraycopy(p, 0, data, 0, p.length);
        System.arraycopy(d, 0, data, p.length, d.length);
        byte[] rv = new byte[32];
        ctx.sha().calculateHash(data, 0, data.length, rv, 0);
        return rv;
    }

    /**
     * Sign the structure using the supplied signing key.
     * Overridden because we sign the inner, then blind and encrypt
     * and sign the outer.
     *
     * @throws IllegalStateException if already signed
     */
    @Override
    public void sign(SigningPrivateKey key) throws DataFormatException {
        // now sign inner with the unblinded key 
        // inner LS is always unpublished
        int saveFlags = _flags;
        setUnpublished();
        super.sign(key);
        _flags = saveFlags;
        if (_log.shouldDebug()) {
            _log.debug("Sign inner with key: " + key.getType() + ' ' + key.toBase64());
            _log.debug("Corresponding pubkey: " + key.toPublic());
            _log.debug("Inner sig: " + _signature.getType() + ' ' + _signature.toBase64());
        }
        encrypt(null);
        SigningPrivateKey bkey = Blinding.blind(key, _alpha);
        int len = size();
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 + len);
        try {
            // unlike LS1, sig covers type
            out.write(getType());
            writeBytesWithoutSig(out);
        } catch (IOException ioe) {
            throw new DataFormatException("Signature failed", ioe);
        }
        byte data[] = out.toByteArray();
        // now sign outer with the blinded key 
        _signature = DSAEngine.getInstance().sign(data, bkey);
        if (_signature == null)
            throw new DataFormatException("Signature failed with " + key.getType() + " key");
        if (_log.shouldDebug()) {
            _log.debug("Sign outer with key: " + bkey.getType() + ' ' + bkey.toBase64());
            _log.debug("Corresponding pubkey: " + bkey.toPublic());
            _log.debug("Outer sig: " + _signature.getType() + ' ' + _signature.toBase64());
        }
    }

    /**
     * Overridden to decrypt if possible, and verify inner sig also.
     *
     * Must call setDestination() prior to this if attempting decryption.
     *
     * @return valid
     */
    @Override
    public boolean verifySignature() {
        // TODO use fields in super
        if (_decryptedLS2 != null)
            return _decryptedLS2.verifySignature();
        if (_log.shouldDebug()) {
            _log.debug("Sig verify outer with key: " + _signingKey.getType() + ' ' + _signingKey.toBase64());
            _log.debug("Outer sig: " + _signature.getType() + ' ' + _signature.toBase64());
        }
        if (!super.verifySignature()) {
            _log.warn("ELS2 outer sig verify fail");
            return false;
        }
        _log.info("ELS2 outer sig verify success");
        if (_unblindedSPK == null) {
            if (_log.shouldWarn())
                _log.warn("ELS2 no dest/SPK to decrypt with", new Exception("I did it"));
            return true;
        }
        try {
            decrypt();
        } catch (DataFormatException dfe) {
            _log.warn("ELS2 decrypt fail", dfe);
            return false;
        } catch (IOException ioe) {
            _log.warn("ELS2 decrypt fail", ioe);
            return false;
        }
        if (_log.shouldDebug()) {
            _log.debug("Decrypted inner LS2:\n" + _decryptedLS2);
            _log.debug("Sig verify inner with key: " + _decryptedLS2.getDestination().getSigningPublicKey().getType() + ' ' + _decryptedLS2.getDestination().getSigningPublicKey().toBase64());
            _log.debug("Inner sig: " + _decryptedLS2.getSignature().getType() + ' ' + _decryptedLS2.getSignature().toBase64());
        }
        boolean rv = _decryptedLS2.verifySignature();
        if (!rv)
            _log.warn("ELS2 inner sig verify fail");
        else
            _log.info("ELS2 inner sig verify success");
        return rv;
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
        if (_signingKey != null) {
            buf.append("\n\tBlinded Key: ").append(_signingKey);
            Hash h = getHash();
            buf.append("\n\tHash: ").append(h);
            buf.append("\n\tB32: ").append(h.toBase32());
        }
        if (isOffline()) {
            buf.append("\n\tTransient Key: ").append(_transientSigningPublicKey);
            buf.append("\n\tTransient Expires: ").append(new java.util.Date(_transientExpires));
            buf.append("\n\tOffline Signature: ").append(_offlineSignature);
        }
        buf.append("\n\tUnpublished? ").append(isUnpublished());
        buf.append("\n\tLength: ").append(_encryptedData.length);
        buf.append("\n\tSignature: ").append(_signature);
        buf.append("\n\tPublished: ").append(new java.util.Date(_published));
        buf.append("\n\tExpires: ").append(new java.util.Date(_expires));
        if (_decryptedLS2 != null) {
            buf.append("\n\tDecrypted LS:\n").append(_decryptedLS2);
        } else if (_destination != null) {
            buf.append("\n\tDestination: ").append(_destination);
            buf.append("\n\tLeases: #").append(getLeaseCount());
            for (int i = 0; i < getLeaseCount(); i++) {
                buf.append("\n\t\t").append(getLease(i));
            }
        } else {
            buf.append("\n\tNot decrypted");
        }
        buf.append("]");
        return buf.toString();
    }

/****
    public static void main(String args[]) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: EncryptedLeaseSet privatekeyfile.dat");
            System.exit(1);
        }
        java.io.File f = new java.io.File(args[0]);
        PrivateKeyFile pkf = new PrivateKeyFile(f);
        pkf.createIfAbsent(SigType.EdDSA_SHA512_Ed25519);
        System.out.println("Online test");
        java.io.File f2 = new java.io.File("online-encls2.dat");
        test(pkf, f2, false);
        //System.out.println("Offline test");
        //f2 = new java.io.File("offline-encls2.dat");
        //test(pkf, f2, true);
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
        ls2.setSecret("foobar");
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
        System.out.println("\nCreated: " + ls2);
        if (!ls2.verifySignature()) {
            System.out.println("Verify FAILED");
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ls2.writeBytes(out);
        java.io.OutputStream out2 = new java.io.FileOutputStream(outfile);
        ls2.writeBytes(out2);
        out2.close();
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(out.toByteArray());
        System.out.println("\nSize calculated: " + (ls2.size() + ls2.getSignature().length()));
        System.out.println("\nSize to read in: " + in.available());
        EncryptedLeaseSet ls3 = new EncryptedLeaseSet();
        ls3.readBytes(in);
        System.out.println("\nRead back: " + ls3);
        // required to decrypt
        ls3.setDestination(pkf.getDestination());
        if (!ls3.verifySignature())
            System.out.println("Verify FAILED");
    }
****/
}
