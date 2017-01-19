package net.i2p.util;

// uncomment to test reference implementation
//import com.github.emboss.siphash.*;

import net.i2p.crypto.SipHashInline;

/**
 *  Wrapper around SipHashInline with constant per-JVM keys
 *
 *  @since 0.9.5
 */
public abstract class SipHash {

    private static final long K0 = RandomSource.getInstance().nextLong();
    private static final long K1 = RandomSource.getInstance().nextLong();

    /**
     *  @param data non-null
     */
    public static long digest(byte[] data) {
        return SipHashInline.hash24(K0, K1, data);
    }

    /**
     *  @param data non-null
     */
    public static long digest(byte[] data, int off, int len) {
        return SipHashInline.hash24(K0, K1, data, off, len);
    }

    /**
     *  Secure replacement for DataHelper.hashCode(byte[]);
     *  caching recommended
     *
     *  @param data may be null
     */
    public static int hashCode(byte[] data) {
        if (data == null) return 0;
        return (int) SipHashInline.hash24(K0, K1, data);
    }

/****
    public static void main(String args[]) {
        final int warmup = 10000;
        final int runs = 1000000;
        final byte[] b = new byte[32];
        RandomSource.getInstance().nextBytes(b);

        // inline implementation
        for (int i = 0; i < warmup; i++) {
            digest(b);
        }
        long begin = System.currentTimeMillis();
        for (int i = 0; i < runs; i++) {
            digest(b);
        }
        System.out.println("Inline impl. time per hash (us): " + (1000d * (System.currentTimeMillis() - begin) / runs));

        // reference implementation
        final byte[] key = new byte[16];
        RandomSource.getInstance().nextBytes(key);
        final SipKey sk = new SipKey(key);
        for (int i = 0; i < warmup; i++) {
            com.github.emboss.siphash.SipHash.digest(sk, b);
        }
        begin = System.currentTimeMillis();
        for (int i = 0; i < runs; i++) {
            com.github.emboss.siphash.SipHash.digest(sk, b);
        }
        System.out.println("Ref. impl. time per hash (us): " + (1000d * (System.currentTimeMillis() - begin) / runs));

        // test results (eeepc openjdk 6) ~2.05 us inline, 2.75 us stock, inline ~25% faster
        // test results (hexcore openjdk 7) ~0.07 us inline, 0.11 us stock, inline ~35% faster
    }
****/
}
