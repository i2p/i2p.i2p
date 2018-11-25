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

import java.math.BigInteger;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.SimpleByteCache;

/** 
 * Wrapper for ElGamal encryption/signature schemes.
 *
 * Does all of Elgamal now for data sizes of 222 bytes and less.  The data to be
 * encrypted is first prepended with a random nonzero byte, then the 32 bytes
 * making up the SHA256 of the data, then the data itself.  The random byte and 
 * the SHA256 hash is stripped on decrypt so the original data is returned.
 *
 * @author thecrypto, jrandom
 */

public final class ElGamalEngine {
    private final Log _log;
    private final I2PAppContext _context;
    private final YKGenerator _ykgen;

    private static final BigInteger ELGPM1 = CryptoConstants.elgp.subtract(BigInteger.ONE);
    private static final int ELG_CLEARTEXT_LENGTH = 222;
    private static final int ELG_ENCRYPTED_LENGTH = 514;
    private static final int ELG_HALF_LENGTH = ELG_ENCRYPTED_LENGTH / 2;

    
    /** 
     * The ElGamal engine should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    public ElGamalEngine(I2PAppContext context) {
        context.statManager().createRequiredRateStat("crypto.elGamal.encrypt",
                                             "Time for ElGamal encryption (ms)", "Encryption",
                                             new long[] { 60 * 60 * 1000});
        context.statManager().createRequiredRateStat("crypto.elGamal.decrypt",
                                             "Time for ElGamal decryption (ms)", "Encryption",
                                             new long[] { 60 * 60 * 1000});
        _context = context;
        _log = context.logManager().getLog(ElGamalEngine.class);
        _ykgen = new YKGenerator(context);
        _ykgen.start();
    }

    /**
     *  Note that this stops the precalc thread and it cannot be restarted.
     *  @since 0.8.8
     */
    public void shutdown() {
        _ykgen.shutdown();
        SigUtil.clearCaches();
    }

    /**
     *  This is now a noop. Cannot be restarted.
     *  @since 0.8.8
     */
    public void restart() {
    }

    private BigInteger[] getNextYK() {
        return _ykgen.getNextYK();
    }

    /** encrypt the data to the public key
     * @return encrypted data, will be exactly 514 bytes long
     *         Contains the two-part encrypted data starting at bytes 0 and 257.
     *         If the encrypted parts are smaller than 257 bytes, they will be
     *         padded with leading zeros.
     *         The parts appear to always be 256 bytes or less, in other words,
     *         bytes 0 and 257 are always zero.
     * @param publicKey public key encrypt to
     * @param data data to encrypt, must be 222 bytes or less
     *         As the encrypted data may contain a substantial number of zeros if the
     *         cleartext is smaller than 222 bytes, it is recommended that the caller pad
     *         the cleartext to 222 bytes with random data.
     */
    public byte[] encrypt(byte data[], PublicKey publicKey) {
        if ((data == null) || (data.length > ELG_CLEARTEXT_LENGTH))
            throw new IllegalArgumentException("Data to encrypt must be <= 222 bytes");
        if (publicKey == null) throw new IllegalArgumentException("Null public key specified");

        long start = _context.clock().now();

        byte d2[] = new byte[1+Hash.HASH_LENGTH+data.length];
        // random nonzero byte
        do {
            _context.random().nextBytes(d2, 0, 1);
        } while (d2[0] == 0);
        _context.sha().calculateHash(data, 0, data.length, d2, 1);
        System.arraycopy(data, 0, d2, 1+Hash.HASH_LENGTH, data.length);
        
        //long t0 = _context.clock().now();
        BigInteger m = new NativeBigInteger(1, d2);
        //long t1 = _context.clock().now();
        if (m.compareTo(CryptoConstants.elgp) >= 0)
            throw new IllegalArgumentException("ARGH.  Data cannot be larger than the ElGamal prime.  FIXME");
        //long t2 = _context.clock().now();
        BigInteger aalpha = new NativeBigInteger(1, publicKey.getData());
        //long t3 = _context.clock().now();
        BigInteger yk[] = getNextYK();
        BigInteger k = yk[1];
        BigInteger y = yk[0];

        //long t7 = _context.clock().now();
        BigInteger d = aalpha.modPow(k, CryptoConstants.elgp);
        //long t8 = _context.clock().now();
        d = d.multiply(m);
        //long t9 = _context.clock().now();
        d = d.mod(CryptoConstants.elgp);
        //long t10 = _context.clock().now();

        byte[] ybytes = y.toByteArray();
        byte[] dbytes = d.toByteArray();
        byte[] out = new byte[ELG_ENCRYPTED_LENGTH];
        System.arraycopy(ybytes, 0, out, (ybytes.length < ELG_HALF_LENGTH ? ELG_HALF_LENGTH - ybytes.length : 0),
                         (ybytes.length > ELG_HALF_LENGTH ? ELG_HALF_LENGTH : ybytes.length));
        System.arraycopy(dbytes, 0, out, (dbytes.length < ELG_HALF_LENGTH ? ELG_ENCRYPTED_LENGTH - dbytes.length : ELG_HALF_LENGTH),
                         (dbytes.length > ELG_HALF_LENGTH ? ELG_HALF_LENGTH : dbytes.length));
        /*
        StringBuilder buf = new StringBuilder(1024);
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
         */
        long end = _context.clock().now();

        long diff = end - start;
        if (diff > 1000) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Took too long to encrypt ElGamal block (" + diff + "ms)");
        }

        _context.statManager().addRateData("crypto.elGamal.encrypt", diff);
        return out;
    }


    /** Decrypt the data
     * @param encrypted encrypted data, must be exactly 514 bytes
     *         Contains the two-part encrypted data starting at bytes 0 and 257.
     *         If the encrypted parts are smaller than 257 bytes, they must be
     *         padded with leading zeros.
     * @param privateKey private key to decrypt with
     * @return unencrypted data or null on failure
     */
    public byte[] decrypt(byte encrypted[], PrivateKey privateKey) {
        if ((encrypted == null) || (encrypted.length != ELG_ENCRYPTED_LENGTH))
            throw new IllegalArgumentException("Data to decrypt must be exactly ELG_ENCRYPTED_LENGTH bytes");
        long start = _context.clock().now();

        BigInteger a = new NativeBigInteger(1, privateKey.getData());
        BigInteger y1p = ELGPM1.subtract(a);
        // we use this buf first for Y, then for D, then for the hash
        byte[] buf = SimpleByteCache.acquire(ELG_HALF_LENGTH);
        System.arraycopy(encrypted, 0, buf, 0, ELG_HALF_LENGTH);
        NativeBigInteger y = new NativeBigInteger(1, buf);
        BigInteger ya = y.modPowCT(y1p, CryptoConstants.elgp);
        System.arraycopy(encrypted, ELG_HALF_LENGTH, buf, 0, ELG_HALF_LENGTH);
        BigInteger d = new NativeBigInteger(1, buf);
        BigInteger m = ya.multiply(d);
        m = m.mod(CryptoConstants.elgp);
        byte val[] = m.toByteArray();
        int i;
        for (i = 0; i < val.length; i++) {
            if (val[i] != (byte) 0x00) break;
        }

        int payloadLen = val.length - i - 1 - Hash.HASH_LENGTH;
        if (payloadLen < 0) {
            if (_log.shouldLog(Log.ERROR)) 
                _log.error("Decrypted data is too small (" + (val.length - i)+ ")");
            return null;
        }

        //ByteArrayInputStream bais = new ByteArrayInputStream(val, i, val.length - i);
        //byte hashData[] = new byte[Hash.HASH_LENGTH];
        //System.arraycopy(val, i + 1, hashData, 0, Hash.HASH_LENGTH);
        //Hash hash = new Hash(hashData);
        //Hash hash = Hash.create(val, i + 1);
        byte rv[] = new byte[payloadLen];
        System.arraycopy(val, i + 1 + Hash.HASH_LENGTH, rv, 0, rv.length);

        // we reuse buf here for the calculated hash
        _context.sha().calculateHash(rv, 0, payloadLen, buf, 0);
        boolean ok = DataHelper.eq(buf, 0, val, i + 1, Hash.HASH_LENGTH);
        SimpleByteCache.release(buf);
        
        long end = _context.clock().now();

        long diff = end - start;
        if (diff > 1000) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Took too long to decrypt and verify ElGamal block (" + diff + "ms)");
        }

        _context.statManager().addRateData("crypto.elGamal.decrypt", diff);

        if (ok) {
            //_log.debug("Hash matches: " + DataHelper.toString(hash.getData(), hash.getData().length));
            return rv;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Doesn't match hash data = "
                       + Base64.encode(rv), new Exception("Doesn't match"));
        return null;
    }
}
