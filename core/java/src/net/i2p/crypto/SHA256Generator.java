package net.i2p.crypto;

import gnu.crypto.hash.Sha256Standalone;

import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.Hash;

/** 
 * Defines a wrapper for SHA-256 operation.  All the good stuff occurs
 * in the GNU-Crypto {@link gnu.crypto.hash.Sha256Standalone}
 * 
 */
public final class SHA256Generator {
    private final LinkedBlockingQueue<Sha256Standalone> _digestsGnu;
    public SHA256Generator(I2PAppContext context) {
        _digestsGnu = new LinkedBlockingQueue(32);
    }
    
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
        Sha256Standalone digest = acquireGnu();
        digest.update(source, start, len);
        byte rv[] = digest.digest();
        releaseGnu(digest);
        //return new Hash(rv);
        return Hash.create(rv);
    }
    
    public final void calculateHash(byte[] source, int start, int len, byte out[], int outOffset) {
        Sha256Standalone digest = acquireGnu();
        digest.update(source, start, len);
        byte rv[] = digest.digest();
        releaseGnu(digest);
        System.arraycopy(rv, 0, out, outOffset, rv.length);
    }
    
    private Sha256Standalone acquireGnu() {
        Sha256Standalone rv = _digestsGnu.poll();
        if (rv != null)
            rv.reset();
        else
            rv = new Sha256Standalone();
        return rv;
    }
    
    private void releaseGnu(Sha256Standalone digest) {
        _digestsGnu.offer(digest);
    }
    
    public static void main(String args[]) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        for (int i = 0; i < args.length; i++)
            System.out.println("SHA256 [" + args[i] + "] = [" + Base64.encode(ctx.sha().calculateHash(args[i].getBytes()).getData()) + "]");
    }
}
