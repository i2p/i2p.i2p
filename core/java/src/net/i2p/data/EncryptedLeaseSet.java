package net.i2p.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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
             + 12
             + _encryptedData.length;
        if (isOffline())
            rv += 2 + _transientSigningPublicKey.length() + _offlineSignature.length();
        return rv;
    }

    // encrypt / decrypt TODO

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
        buf.append("\n\tSignature: ").append(_signature);
        buf.append("\n\tPublished: ").append(new java.util.Date(_published));
        buf.append("\n\tExpires: ").append(new java.util.Date(_expires));
        buf.append("]");
        return buf.toString();
    }
}
