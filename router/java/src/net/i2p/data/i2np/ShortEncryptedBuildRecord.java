package net.i2p.data.i2np;

/**
 *  Small records.
 *  236 bytes.
 *  Preliminary, see proposal 157.
 *
 *  Note that these are layer-encrypted and layer-decrypted in-place.
 *  Do not cache.
 *
 *  @since 0.9.49
 */
public class ShortEncryptedBuildRecord extends EncryptedBuildRecord {

    public final static int LENGTH = ShortTunnelBuildMessage.SHORT_RECORD_SIZE;

    /** @throws IllegalArgumentException if data is not correct length (null is ok) */
    public ShortEncryptedBuildRecord(byte data[]) {
        super(data);
    }

    @Override
    public int length() {
        return LENGTH;
    }
}
