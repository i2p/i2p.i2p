package net.i2p.crypto;

import java.util.Arrays;
import net.i2p.I2PAppContext;
import net.i2p.data.Hash;

import org.bouncycastle.crypto.digests.SHA256Digest;

/** 
 * Defines a wrapper for SHA-256 operation.  All the good stuff occurs
 * in the Bouncycastle {@link org.bouncycastle.crypto.digests.SHA256Digest}
 * 
 */
public final class SHA256Generator {
    public SHA256Generator(I2PAppContext context) {}
    
    public static final SHA256Generator getInstance() {
        return I2PAppContext.getGlobalContext().sha();
    }
    
    /** Calculate the SHA-256 has of the source
     * @param source what to hash
     * @return hash of the source
     */
    public final Hash calculateHash(byte[] source) {
        return calculateHash(source, 0, source.length);
    }
    public final Hash calculateHash(byte[] source, int start, int len) {
        byte rv[] = new byte[Hash.HASH_LENGTH];
        SHA256Digest digest = new SHA256Digest();
        digest.update(source, start, len);
        digest.doFinal(rv, 0);
        return new Hash(rv);
    }
    
    public static void main(String args[]) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        byte orig[] = new byte[4096];
        ctx.random().nextBytes(orig);
        Hash old = ctx.sha().calculateHash(orig);
        SHA256Digest d = new SHA256Digest();
        d.update(orig, 0, orig.length);
        byte out[] = new byte[Hash.HASH_LENGTH];
        d.doFinal(out, 0);
        System.out.println("eq? " + net.i2p.data.DataHelper.eq(out, old.getData()));
    }
}