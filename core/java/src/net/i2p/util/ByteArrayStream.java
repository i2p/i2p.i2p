package net.i2p.util;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

import net.i2p.I2PAppContext;
import net.i2p.crypto.DSAEngine;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

/**
 *  OutputStream to InputStream adapter.
 *  Zero-copy where possible. Unsynchronized.
 *  This is NOT a Pipe.
 *  Do NOT reset after writing.
 *
 *  @since 0.9.48
 */
public class ByteArrayStream extends ByteArrayOutputStream {

    public ByteArrayStream() {
        super();
    }

    /**
     *  @param size if accurate, toByteArray() will be zero-copy
     */
    public ByteArrayStream(int size) {
        super(size);
    }

    /**
     *  @throws IllegalStateException if previously written
     */
    @Override
    public void reset() {
        if (count > 0)
            throw new IllegalStateException();
    }

    /**
     *  Zero-copy only if the data fills the buffer.
     *  Use asInputStream() for guaranteed zero-copy.
     */
    @Override
    public byte[] toByteArray() {
        if (count == buf.length)
            return buf;
        return Arrays.copyOfRange(buf, 0, count);
    }

    /**
     *  All data previously written. Zero-copy. Not a Pipe.
     *  Data written after this call will not appear.
     */
    public ByteArrayInputStream asInputStream() {
        return new ByteArrayInputStream(buf, 0, count);
    }

    /**
     *  Copy all data to the target
     */
    public void copyTo(byte[] target, int offset) {
        System.arraycopy(buf, 0, target, offset, count);
    }

    /**
     *  Verify the written data
     */
    public boolean verifySignature(Signature signature, SigningPublicKey verifyingKey) {
        return DSAEngine.getInstance().verifySignature(signature, buf, 0, count, verifyingKey);
    }

    /**
     *  Verify the written data
     */
    public boolean verifySignature(I2PAppContext ctx, Signature signature, SigningPublicKey verifyingKey) {
        return ctx.dsa().verifySignature(signature, buf, 0, count, verifyingKey);
    }

    /**
     *  Sign the written data
     *  @return null on error
     */
    public Signature sign(SigningPrivateKey signingKey) {
        return DSAEngine.getInstance().sign(buf, 0, count, signingKey);
    }

    /**
     *  Sign the written data
     *  @return null on error
     */
    public Signature sign(I2PAppContext ctx, SigningPrivateKey signingKey) {
        return ctx.dsa().sign(buf, 0, count, signingKey);
    }
}
