package net.i2p.crypto;

import gnu.crypto.hash.Sha256Standalone;
import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.Mac;
import org.bouncycastle.crypto.macs.I2PHMac;

/**
 * Calculate the HMAC-SHA256 of a key+message.  All the good stuff occurs
 * in {@link org.bouncycastle.crypto.macs.HMac} and 
 * {@link org.bouncycastle.crypto.digests.MD5Digest}.
 *
 */
public class HMAC256Generator extends HMACGenerator {
    public HMAC256Generator(I2PAppContext context) { super(context); }
    
    @Override
    protected I2PHMac acquire() {
        synchronized (_available) {
            if (_available.size() > 0)
                return (I2PHMac)_available.remove(0);
        }
        // the HMAC is hardcoded to use SHA256 digest size
        // for backwards compatability.  next time we have a backwards
        // incompatible change, we should update this by removing ", 32"
        return new I2PHMac(new Sha256ForMAC());
    }
    
    private class Sha256ForMAC extends Sha256Standalone implements Digest {
        public String getAlgorithmName() { return "sha256 for hmac"; }
        public int getDigestSize() { return 32; }
        public int doFinal(byte[] out, int outOff) {
            byte rv[] = digest();
            System.arraycopy(rv, 0, out, outOff, rv.length);
            reset();
            return rv.length;
        }
        
    }
    
    public static void main(String args[]) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        byte data[] = new byte[64];
        ctx.random().nextBytes(data);
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        Hash mac = ctx.hmac256().calculate(key, data);
        System.out.println(Base64.encode(mac.getData()));
    }
}
