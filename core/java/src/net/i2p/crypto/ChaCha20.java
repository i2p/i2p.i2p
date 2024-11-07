package net.i2p.crypto;

/*
 * Contains code from Noise ChaChaPolyCipherState:
 *
 * Copyright (C) 2016 Southern Storm Software, Pty Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

/*
import java.lang.reflect.Constructor;
import java.security.spec.AlgorithmParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.Cipher;
import freenet.support.CPUInformation.CPUID;
import freenet.support.CPUInformation.CPUInfo;
import net.i2p.util.SystemVersion;
*/

import com.southernstorm.noise.crypto.chacha20.ChaChaCore;

import net.i2p.data.DataHelper;

/**
 * ChaCha20, wrapper around Noise ChaChaCore.
 * RFC 7539
 *
 * @since 0.9.39
 */
public final class ChaCha20 {


    private ChaCha20() {}

    /**
     * Encrypt from plaintext to ciphertext
     *
     * @param key first 32 bytes used as the key
     * @param iv first 12 bytes used as the iv
     */
    public static void encrypt(byte[] key, byte[] iv,
                               byte[] plaintext, int plaintextOffset,
                               byte[] ciphertext, int ciphertextOffset, int length) {
        encrypt(key, iv, 0, plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
    }

    /**
     * Encrypt from plaintext to ciphertext
     *
     * @param key first 32 bytes used as the key
     * @param iv first 12 bytes starting at ivOffset used as the iv
     * @since 0.9.54
     */
    public static void encrypt(byte[] key, byte[] iv, int ivOffset,
                               byte[] plaintext, int plaintextOffset,
                               byte[] ciphertext, int ciphertextOffset, int length) {
/*
        if (USE_SYSTEM_CHACHA && !_systemFailed && length >= MIN_LEN_FOR_SYSTEM) {
            try {
                 if (ivOffset != 0 || iv.length != 12) {
                     byte[] niv = new byte[12];
                     System.arraycopy(iv, ivOffset, niv, 0, 12);
                     iv = niv;
                 }
                 AlgorithmParameterSpec spec = (AlgorithmParameterSpec) _speccon.newInstance(iv, 1);
                 SecretKeySpec jkey = new SecretKeySpec(key, 0, 32, "ChaCha20");
                 // TODO This is probably the slow part, maybe cache some Ciphers
                 Cipher c = Cipher.getInstance("ChaCha20");
                 c.init(Cipher.ENCRYPT_MODE, jkey, spec);
                 c.doFinal(plaintext, plaintextOffset, length, ciphertext, ciphertextOffset);
                 return;
            } catch (Exception e) {
                System.out.println("System ChaCha failure");
                e.printStackTrace();
                _systemFailed = true;
            }
        }
*/

        int[] input = new int[16];
        int[] output = new int[16];
        ChaChaCore.initKey256(input, key, 0);
        //System.out.println("initkey");
        //dumpBlock(input);
        // RFC 7539
        // block counter
        input[12] = 1;
        // Words 13-15 are a nonce, which should not be repeated for the same
        // key.  The 13th word is the first 32 bits of the input nonce taken
        // as a little-endian integer, while the 15th word is the last 32
        // bits.
        //ChaChaCore.initIV(input, iv, counter);
        //ChaChaCore.initIV(input, iv[4:11], iv[0:3]);
        input[13] = (int) DataHelper.fromLongLE(iv, ivOffset, 4);
        input[14] = (int) DataHelper.fromLongLE(iv, ivOffset + 4, 4);
        input[15] = (int) DataHelper.fromLongLE(iv, ivOffset + 8, 4);
        //System.out.println("initIV");
        //dumpBlock(input);
        ChaChaCore.hash(output, input);
        //int ctr = 1;
        //System.out.println("hash " + ctr);
        //dumpBlock(output);
        while (length > 0) {
            int tempLen = 64;
            if (tempLen > length)
                tempLen = length;
            ChaChaCore.hash(output, input);
            //System.out.println("hash " + ++ctr);
            //dumpBlock(output);
            ChaChaCore.xorBlock(plaintext, plaintextOffset, ciphertext, ciphertextOffset, tempLen, output);
            if (++(input[12]) == 0)
                ++(input[13]);
            plaintextOffset += tempLen;
            ciphertextOffset += tempLen;
            length -= tempLen;
        }
    }

    /**
     * Encrypt from ciphertext to plaintext
     *
     * @param key first 32 bytes used as the key
     * @param iv first 12 bytes used as the iv
     */
    public static void decrypt(byte[] key, byte[] iv,
                               byte[] ciphertext, int ciphertextOffset,
                               byte[] plaintext, int plaintextOffset, int length) {
        // it's symmetric!
        encrypt(key, iv, 0, ciphertext, ciphertextOffset, plaintext, plaintextOffset, length);
    }

    /**
     * Encrypt from ciphertext to plaintext
     *
     * @param key first 32 bytes used as the key
     * @param iv first 12 bytes starting at ivOffset used as the iv
     * @since 0.9.54
     */
    public static void decrypt(byte[] key, byte[] iv, int ivOffset,
                               byte[] ciphertext, int ciphertextOffset,
                               byte[] plaintext, int plaintextOffset, int length) {
        // it's symmetric!
        encrypt(key, iv, ivOffset, ciphertext, ciphertextOffset, plaintext, plaintextOffset, length);
    }

/*
    private static final boolean USE_SYSTEM_CHACHA = useSystemChaCha();
    private static final boolean USE_AVX_CHACHA = useAVXChaCha();
    private static final int MIN_LEN_FOR_SYSTEM = USE_SYSTEM_CHACHA ? (USE_AVX_CHACHA ? 1280 : 2500) : 0;
    private static final Constructor<?> _speccon;
    private static boolean _systemFailed;
*/

    /**
     *  Requires Java 11 and JCE support.
     *  @since 0.9.xx
     */
/*
    private static boolean useSystemChaCha() {
        if (SystemVersion.isJava11() && !SystemVersion.isAndroid() &&
            !SystemVersion.isApache() && !SystemVersion.isGNU()) {
            try {
                Cipher.getInstance("ChaCha20");
                Class.forName("javax.crypto.spec.ChaCha20ParameterSpec", false, ClassLoader.getSystemClassLoader());
                return true;
            } catch (Exception e) {}
        }
        return false;
    }
*/

    /**
     *  Requires Java 20, 64 bit, x86/ARM, AVX, and JCE support.
     *  Ref: https://bugs.openjdk.org/browse/JDK-8247645
     *  @since 0.9.xx
     */
/*
    private static boolean useAVXChaCha() {
        if (USE_SYSTEM_CHACHA &&
            SystemVersion.isX86() && SystemVersion.is64Bit() && SystemVersion.isJava(20)) {
            try {
                return SystemVersion.isARM() || CPUID.getInfo().hasAVX();
            } catch (Exception e) {}
        }
        return false;
    }

    static {
        Constructor<?> con = null;
        if (USE_SYSTEM_CHACHA) {
            try {
                Class<?> cl = Class.forName("javax.crypto.spec.ChaCha20ParameterSpec", true, ClassLoader.getSystemClassLoader());
                con = cl.getConstructor(byte[].class, int.class);
            } catch (Exception e) {
                System.out.println("System ChaCha failure");
                e.printStackTrace();
                _systemFailed = true;
            }
        }
        _speccon = con;
    }
*/
    /**
     *  97% of calls are 1024 bytes or less
     *  JCE is slower or the same for smaller sizes, even with AVX/AVX2.
     *  Even for larger sizes, the improvement is modest.
     *  AVX512 untested.
     */
/****
    public static void main(String[] args) throws Exception {
        // vectors as in RFC 7539
        byte[] plaintext = DataHelper.getASCII("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.");
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) i;
        }
        byte[] iv = new byte[12];
        iv[7] = 0x4a;
        byte[] out = new byte[plaintext.length];
        encrypt(key, iv, plaintext, 0, out, 0, plaintext.length);
        //  Ciphertext Sunscreen:
        //  000  6e 2e 35 9a 25 68 f9 80 41 ba 07 28 dd 0d 69 81  n.5.%h..A..(..i.
        //  016  e9 7e 7a ec 1d 43 60 c2 0a 27 af cc fd 9f ae 0b  .~z..C`..'......
        //  032  f9 1b 65 c5 52 47 33 ab 8f 59 3d ab cd 62 b3 57  ..e.RG3..Y=..b.W
        //  048  16 39 d6 24 e6 51 52 ab 8f 53 0c 35 9f 08 61 d8  .9.$.QR..S.5..a.
        //  064  07 ca 0d bf 50 0d 6a 61 56 a3 8e 08 8a 22 b6 5e  ....P.jaV....".^
        //  080  52 bc 51 4d 16 cc f8 06 81 8c e9 1a b7 79 37 36  R.QM.........y76
        //  096  5a f9 0b bf 74 a3 5b e6 b4 0b 8e ed f2 78 5e 42  Z...t.[......x^B
        //  112  87 4d                                            .M
        System.out.println("Ciphertext:\n" + net.i2p.util.HexDump.dump(out));
        byte[] out2 = new byte[plaintext.length];
        decrypt(key, iv, out, 0, out2, 0, plaintext.length);
        System.out.println("Plaintext:\n" + net.i2p.util.HexDump.dump(out2));

        if (USE_SYSTEM_CHACHA) {
            // results may change in later JVMs if this is implemented:
            // https://bugs.openjdk.org/browse/JDK-8302007
            boolean HAS_AVX = false;
            boolean HAS_AVX2 = false;
            boolean HAS_AVX512 = false;
            try {
                CPUInfo info = CPUID.getInfo();
                HAS_AVX = info.hasAVX();
                HAS_AVX2 = info.hasAVX2();
                HAS_AVX512 = HAS_AVX2 && info.hasAVX512();
            } catch (Exception e) {}
            System.out.println("Java:       " + System.getProperty("java.version"));
            System.out.println("Has AVX?    " + HAS_AVX);
            System.out.println("Has AVX2?   " + HAS_AVX2);
            System.out.println("Has AVX512? " + HAS_AVX512);
            Cipher c = Cipher.getInstance("ChaCha20");
            AlgorithmParameterSpec spec = (AlgorithmParameterSpec) _speccon.newInstance(iv, 1);
            SecretKeySpec jkey = new SecretKeySpec(key, "ChaCha20");
            c.init(Cipher.ENCRYPT_MODE, jkey, spec);
            out = c.doFinal(plaintext);
            System.out.println("Ciphertext:\n" + net.i2p.util.HexDump.dump(out));
            c = Cipher.getInstance("ChaCha20");
            c.init(Cipher.DECRYPT_MODE, jkey, spec);
            out2 = c.doFinal(out);
            System.out.println("Plaintext:\n" + net.i2p.util.HexDump.dump(out2));

            System.out.println("JCE w/Poly1305 version");
            c = Cipher.getInstance("ChaCha20-Poly1305");
            javax.crypto.spec.IvParameterSpec ispec = new javax.crypto.spec.IvParameterSpec(iv);
            c.init(Cipher.ENCRYPT_MODE, jkey, ispec);
            out = c.doFinal(plaintext);
            System.out.println("Ciphertext:\n" + net.i2p.util.HexDump.dump(out));
            c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.DECRYPT_MODE, jkey, ispec);
            out2 = c.doFinal(out);
            System.out.println("Plaintext:\n" + net.i2p.util.HexDump.dump(out2));
        } else {
            System.out.println("JCE version not available, not testing");
        }

        System.out.println("Warmup");
        test(128, 1000);
        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
        System.out.println("\nSpeed test");
        int runs = 10000;
        // for some reason the second time through gives much faster results
        for (int i = 0; i < 2; i++) {
            test(128, runs);
            test(1024, runs);
            test(1280, runs);
            test(1536, runs);
            test(2048, runs);
            test(2560, runs);
            test(3072, runs);
            test(4096, runs);
            test(8192, runs);
            System.out.println();
        }
    }

    private static void test(int sz, int runs) throws Exception {
        System.out.println("Testing " + sz + " bytes " + runs + " runs:");
        byte[] b = new byte[sz];
        net.i2p.util.RandomSource.getInstance().nextBytes(b);
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) i;
        }
        byte[] iv = new byte[12];
        iv[7] = 0x4a;
        long t = System.currentTimeMillis();
        for (int i = 0; i < runs; i++) {
            encrypt(key, iv, b, 0, b, 0, sz);
        }
        t = System.currentTimeMillis() - t;
        System.out.println("Noise took " + t);
        long noise = t;

        if (USE_SYSTEM_CHACHA) {
            t = System.currentTimeMillis();
            for (int i = 0; i < runs; i++) {
                Cipher c = Cipher.getInstance("ChaCha20");
                AlgorithmParameterSpec spec = (AlgorithmParameterSpec) _speccon.newInstance(iv, 1);
                SecretKeySpec jkey = new SecretKeySpec(key, "ChaCha20");
                c.init(javax.crypto.Cipher.ENCRYPT_MODE, jkey, spec);
                b = c.doFinal(b);
            }
            t = System.currentTimeMillis() - t;
            System.out.println("JCE took " + t);
        }
    }

    private static void dumpBlock(int[] b) {
        byte[] d = new byte[64];
        for (int i = 0; i < 16; i++) {
            //DataHelper.toLongLE(d, i*4, 4, b[i] & 0xffffffffL);
            // use BE so the bytes look right
            DataHelper.toLong(d, i*4, 4, b[i] & 0xffffffffL);
        }
        System.out.println(net.i2p.util.HexDump.dump(d));
    }
****/
}
