package net.i2p.crypto;

/* 
 * Copyright (c) 2003, TheCrypto
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this 
 *   list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, 
 *   this list of conditions and the following disclaimer in the documentation 
 *   and/or other materials provided with the distribution.
 * -  Neither the name of the TheCrypto may be used to endorse or promote 
 *    products derived from this software without specific prior written 
 *    permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.RandomSource;

/** 
 * Wrapper for ElGamal encryption/signature schemes.
 *
 * Does all of Elgamal now for data sizes of 223 bytes and less.  The data to be
 * encrypted is first prepended with a random nonzero byte, then the 32 bytes
 * making up the SHA256 of the data, then the data itself.  The random byte and 
 * the SHA256 hash is stripped on decrypt so the original data is returned.
 *
 * @author thecrypto, jrandom
 */

public class ElGamalEngine {
    private final static Log _log = new Log(ElGamalEngine.class);
    private static ElGamalEngine _engine;
    static {
        if ("off".equals(System.getProperty("i2p.encryption", "on")))
            _engine = new DummyElGamalEngine();
        else
            _engine = new ElGamalEngine();

        StatManager.getInstance().createRateStat("crypto.elGamal.encrypt",
                                                 "how long does it take to do a full ElGamal encryption", "Encryption",
                                                 new long[] { 60 * 1000, 60 * 60 * 1000, 24 * 60 * 60 * 1000});
        StatManager.getInstance().createRateStat("crypto.elGamal.decrypt",
                                                 "how long does it take to do a full ElGamal decryption", "Encryption",
                                                 new long[] { 60 * 1000, 60 * 60 * 1000, 24 * 60 * 60 * 1000});
    }

    public static ElGamalEngine getInstance() {
        return _engine;
    }
    private final static BigInteger _two = new NativeBigInteger(1, new byte[] { 0x02});

    private BigInteger[] getNextYK() {
        return YKGenerator.getNextYK();
    }

    /** encrypt the data to the public key
     * @return encrypted data
     * @param publicKey public key encrypt to
     * @param data data to encrypt
     */
    public byte[] encrypt(byte data[], PublicKey publicKey) {
        if ((data == null) || (data.length >= 223))
            throw new IllegalArgumentException("Data to encrypt must be < 223 bytes at the moment");
        if (publicKey == null) throw new IllegalArgumentException("Null public key specified");

        long start = Clock.getInstance().now();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        try {
            baos.write(0xFF);
            Hash hash = SHA256Generator.getInstance().calculateHash(data);
            hash.writeBytes(baos);
            baos.write(data);
            baos.flush();
        } catch (Exception e) {
            if (_log.shouldLog(Log.ERROR)) _log.error("Internal error writing to buffer", e);
            return null;
        }

        byte d2[] = baos.toByteArray();
        long t0 = Clock.getInstance().now();
        BigInteger m = new NativeBigInteger(1, d2);
        long t1 = Clock.getInstance().now();
        if (m.compareTo(CryptoConstants.elgp) >= 0)
            throw new IllegalArgumentException("ARGH.  Data cannot be larger than the ElGamal prime.  FIXME");
        long t2 = Clock.getInstance().now();
        BigInteger aalpha = new NativeBigInteger(1, publicKey.getData());
        long t3 = Clock.getInstance().now();
        BigInteger yk[] = getNextYK();
        BigInteger k = yk[1];
        BigInteger y = yk[0];

        long t7 = Clock.getInstance().now();
        BigInteger d = aalpha.modPow(k, CryptoConstants.elgp);
        long t8 = Clock.getInstance().now();
        d = d.multiply(m);
        long t9 = Clock.getInstance().now();
        d = d.mod(CryptoConstants.elgp);
        long t10 = Clock.getInstance().now();

        byte[] ybytes = y.toByteArray();
        byte[] dbytes = d.toByteArray();
        byte[] out = new byte[514];
        System.arraycopy(ybytes, 0, out, (ybytes.length < 257 ? 257 - ybytes.length : 0),
                         (ybytes.length > 257 ? 257 : ybytes.length));
        System.arraycopy(dbytes, 0, out, (dbytes.length < 257 ? 514 - dbytes.length : 257),
                         (dbytes.length > 257 ? 257 : dbytes.length));
        StringBuffer buf = new StringBuffer(1024);
        buf.append("Timing\n");
        buf.append("0-1: ").append(t1 - t0).append('\n');
        buf.append("1-2: ").append(t2 - t1).append('\n');
        buf.append("2-3: ").append(t3 - t2).append('\n');
        //buf.append("3-4: ").append(t4-t3).append('\n');
        //buf.append("4-5: ").append(t5-t4).append('\n');
        //buf.append("5-6: ").append(t6-t5).append('\n');
        //buf.append("6-7: ").append(t7-t6).append('\n');
        buf.append("7-8: ").append(t8 - t7).append('\n');
        buf.append("8-9: ").append(t9 - t8).append('\n');
        buf.append("9-10: ").append(t10 - t9).append('\n');
        //_log.debug(buf.toString());
        long end = Clock.getInstance().now();

        long diff = end - start;
        if (diff > 1000) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Took too long to encrypt ElGamal block (" + diff + "ms)");
        }

        StatManager.getInstance().addRateData("crypto.elGamal.encrypt", diff, diff);
        return out;
    }

    /** Decrypt the data
     * @param encrypted encrypted data
     * @param privateKey private key to decrypt with
     * @return unencrypted data
     */
    public byte[] decrypt(byte encrypted[], PrivateKey privateKey) {
        if ((encrypted == null) || (encrypted.length > 514))
            throw new IllegalArgumentException("Data to decrypt must be <= 514 bytes at the moment");
        long start = Clock.getInstance().now();

        byte[] ybytes = new byte[257];
        byte[] dbytes = new byte[257];
        System.arraycopy(encrypted, 0, ybytes, 0, 257);
        System.arraycopy(encrypted, 257, dbytes, 0, 257);
        BigInteger y = new NativeBigInteger(1, ybytes);
        BigInteger d = new NativeBigInteger(1, dbytes);
        BigInteger a = new NativeBigInteger(1, privateKey.getData());
        BigInteger y1p = CryptoConstants.elgp.subtract(BigInteger.ONE).subtract(a);
        BigInteger ya = y.modPow(y1p, CryptoConstants.elgp);
        BigInteger m = ya.multiply(d);
        m = m.mod(CryptoConstants.elgp);
        byte val[] = m.toByteArray();
        int i = 0;
        for (i = 0; i < val.length; i++)
            if (val[i] != (byte) 0x00) break;

        ByteArrayInputStream bais = new ByteArrayInputStream(val, i, val.length - i);
        Hash hash = new Hash();
        byte rv[] = null;
        try {
            bais.read(); // skip first byte
            hash.readBytes(bais);
            rv = new byte[val.length - i - 1 - 32];
            bais.read(rv);
        } catch (Exception e) {
            if (_log.shouldLog(Log.ERROR)) _log.error("Internal error reading value", e);
            return null;
        }

        Hash calcHash = SHA256Generator.getInstance().calculateHash(rv);
        boolean ok = calcHash.equals(hash);

        long end = Clock.getInstance().now();

        long diff = end - start;
        if (diff > 1000) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Took too long to decrypt and verify ElGamal block (" + diff + "ms)");
        }

        StatManager.getInstance().addRateData("crypto.elGamal.decrypt", diff, diff);

        if (ok) {
            //_log.debug("Hash matches: " + DataHelper.toString(hash.getData(), hash.getData().length));
            return rv;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Doesn't match hash [calc=" + calcHash + " sent hash=" + hash + "]\ndata = "
                           + Base64.encode(rv), new Exception("Doesn't match"));
            return null;
        }
    }

    public static void main(String args[]) {
        long eTime = 0;
        long dTime = 0;
        long gTime = 0;
        int numRuns = 100;
        if (args.length > 0) try {
            numRuns = Integer.parseInt(args[0]);
        } catch (NumberFormatException nfe) {
        }

        try {
            Thread.sleep(30 * 1000);
        } catch (InterruptedException ie) {
        }

        RandomSource.getInstance().nextBoolean();

        System.out.println("Running " + numRuns + " times");

        for (int i = 0; i < numRuns; i++) {
            long startG = Clock.getInstance().now();
            Object pair[] = KeyGenerator.getInstance().generatePKIKeypair();
            long endG = Clock.getInstance().now();

            PublicKey pubkey = (PublicKey) pair[0];
            PrivateKey privkey = (PrivateKey) pair[1];
            byte buf[] = new byte[128];
            RandomSource.getInstance().nextBytes(buf);
            long startE = Clock.getInstance().now();
            byte encr[] = ElGamalEngine.getInstance().encrypt(buf, pubkey);
            long endE = Clock.getInstance().now();
            byte decr[] = ElGamalEngine.getInstance().decrypt(encr, privkey);
            long endD = Clock.getInstance().now();
            eTime += endE - startE;
            dTime += endD - endE;
            gTime += endG - startG;

            if (!DataHelper.eq(decr, buf)) {
                System.out.println("PublicKey     : " + DataHelper.toString(pubkey.getData(), pubkey.getData().length));
                System.out.println("PrivateKey    : "
                                   + DataHelper.toString(privkey.getData(), privkey.getData().length));
                System.out.println("orig          : " + DataHelper.toString(buf, buf.length));
                System.out.println("d(e(orig)     : " + DataHelper.toString(decr, decr.length));
                System.out.println("orig.len      : " + buf.length);
                System.out.println("d(e(orig).len : " + decr.length);
                System.out.println("Not equal!");
                System.exit(0);
            } else {
                System.out.println("*Run " + i + " is successful, with encr.length = " + encr.length + " [E: "
                                   + (endE - startE) + " D: " + (endD - endE) + " G: " + (endG - startG) + "]\n");
            }
        }
        System.out.println("\n\nAll " + numRuns + " tests successful, average encryption time: " + (eTime / numRuns)
                           + " average decryption time: " + (dTime / numRuns) + " average key generation time: "
                           + (gTime / numRuns));
    }
}