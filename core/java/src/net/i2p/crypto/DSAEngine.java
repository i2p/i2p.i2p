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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.DSAPrivateKeySpec;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.KeySpec;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;

/**
 *  Sign and verify using DSA-SHA1.
 *  Also contains methods to sign and verify using a SHA-256 Hash, used by Syndie only.
 *
 *  The primary implementation is code from TheCryto.
 *  As of 0.8.7, also included is an alternate implementation using java.security libraries, which
 *  is slightly slower. This implementation could in the future be easily modified
 *  to use a new signing algorithm from java.security when we change the signing algorithm.
 *
 *  Params and rv's changed from Hash to SHA1Hash for version 0.8.1
 *  Hash variants of sign() and verifySignature() restored in 0.8.3, required by Syndie.
 */
public class DSAEngine {
    private final Log _log;
    private final I2PAppContext _context;

    //private static final boolean _isAndroid = System.getProperty("java.vendor").contains("Android");
    private static final boolean _useJavaLibs = false;   // = _isAndroid;

    public DSAEngine(I2PAppContext context) {
        _log = context.logManager().getLog(DSAEngine.class);
        _context = context;
    }

    public static DSAEngine getInstance() {
        return I2PAppContext.getGlobalContext().dsa();
    }

    /**
     *  Verify using DSA-SHA1.
     *  Uses TheCrypto code unless configured to use the java.security libraries.
     */
    public boolean verifySignature(Signature signature, byte signedData[], SigningPublicKey verifyingKey) {
        boolean rv;
        if (_useJavaLibs) {
            try {
                rv = altVerifySigSHA1(signature, signedData, verifyingKey);
                if ((!rv) && _log.shouldLog(Log.WARN))
                    _log.warn("Lib Verify Fail, sig =\n" + signature + "\npubkey =\n" + verifyingKey);
                return rv;
            } catch (GeneralSecurityException gse) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Lib Verify Fail, sig =\n" + signature + "\npubkey =\n" + verifyingKey, gse);
                // now try TheCrypto
            }
        }
        rv = verifySignature(signature, signedData, 0, signedData.length, verifyingKey);
        if ((!rv) && _log.shouldLog(Log.WARN))
            _log.warn("TheCrypto Verify Fail, sig =\n" + signature + "\npubkey =\n" + verifyingKey);
        return rv;
    }

    /**
     *  Verify using DSA-SHA1
     */
    public boolean verifySignature(Signature signature, byte signedData[], int offset, int size, SigningPublicKey verifyingKey) {
        return verifySignature(signature, calculateHash(signedData, offset, size), verifyingKey);
    }

    /**
     *  Verify using DSA-SHA1
     */
    public boolean verifySignature(Signature signature, InputStream in, SigningPublicKey verifyingKey) {
        return verifySignature(signature, calculateHash(in), verifyingKey);
    }

    /** @param hash SHA-1 hash, NOT a SHA-256 hash */
    public boolean verifySignature(Signature signature, SHA1Hash hash, SigningPublicKey verifyingKey) {
        return verifySig(signature, hash, verifyingKey);
    }

    /**
     *  Used by Syndie.
     *  @since 0.8.3 (restored, was removed in 0.8.1 and 0.8.2)
     */
    public boolean verifySignature(Signature signature, Hash hash, SigningPublicKey verifyingKey) {
        return verifySig(signature, hash, verifyingKey);
    }

    /**
     *  @param hash either a Hash or a SHA1Hash
     *  @since 0.8.3
     */
    private boolean verifySig(Signature signature, SimpleDataStructure hash, SigningPublicKey verifyingKey) {
        long start = _context.clock().now();

        try {
            byte[] sigbytes = signature.getData();
            byte rbytes[] = new byte[20];
            byte sbytes[] = new byte[20];
            //System.arraycopy(sigbytes, 0, rbytes, 0, 20);
            //System.arraycopy(sigbytes, 20, sbytes, 0, 20);
            for (int x = 0; x < 40; x++) {
                if (x < 20) {
                    rbytes[x] = sigbytes[x];
                } else {
                    sbytes[x - 20] = sigbytes[x];
                }
            }

            BigInteger s = new NativeBigInteger(1, sbytes);
            BigInteger r = new NativeBigInteger(1, rbytes);
            BigInteger y = new NativeBigInteger(1, verifyingKey.getData());
            BigInteger w = null;
            try {
                w = s.modInverse(CryptoConstants.dsaq);
            } catch (ArithmeticException ae) {
                _log.warn("modInverse() error", ae);
                return false;
            }
            byte data[] = hash.getData();
            NativeBigInteger bi = new NativeBigInteger(1, data);
            BigInteger u1 = bi.multiply(w).mod(CryptoConstants.dsaq);
            BigInteger u2 = r.multiply(w).mod(CryptoConstants.dsaq);
            BigInteger modval = CryptoConstants.dsag.modPow(u1, CryptoConstants.dsap);
            BigInteger modmulval = modval.multiply(y.modPow(u2,CryptoConstants.dsap));
            BigInteger v = (modmulval).mod(CryptoConstants.dsap).mod(CryptoConstants.dsaq);

            boolean ok = v.compareTo(r) == 0;

            long diff = _context.clock().now() - start;
            if (diff > 1000) {
                if (_log.shouldLog(Log.WARN)) 
                    _log.warn("Took too long to verify the signature (" + diff + "ms)");
            }
            return ok;
        } catch (Exception e) {
            _log.log(Log.CRIT, "Error verifying the signature", e);
            return false;
        }
    }

    /**
     *  Sign using DSA-SHA1.
     *  Uses TheCrypto code unless configured to use the java.security libraries.
     */
    public Signature sign(byte data[], SigningPrivateKey signingKey) {
        if (_useJavaLibs) {
            try {
                return altSignSHA1(data, signingKey);
            } catch (GeneralSecurityException gse) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Lib Sign Fail, privkey = " + signingKey, gse);
                // now try TheCrypto
            }
        }
        return sign(data, 0, data.length, signingKey);
    }

    /**
     *  Sign using DSA-SHA1
     */
    public Signature sign(byte data[], int offset, int length, SigningPrivateKey signingKey) {
        if ((signingKey == null) || (data == null) || (data.length <= 0)) return null;
        SHA1Hash h = calculateHash(data, offset, length);
        return sign(h, signingKey);
    }
    
    /**
     *  Sign using DSA-SHA1.
     *  Reads the stream until EOF. Does not close the stream.
     */
    public Signature sign(InputStream in, SigningPrivateKey signingKey) {
        if ((signingKey == null) || (in == null) ) return null;
        SHA1Hash h = calculateHash(in);
        return sign(h, signingKey);
    }

    /** @param hash SHA-1 hash, NOT a SHA-256 hash */
    public Signature sign(SHA1Hash hash, SigningPrivateKey signingKey) {
        return signIt(hash, signingKey);
    }

    /**
     *  Used by Syndie.
     *  @since 0.8.3 (restored, was removed in 0.8.1 and 0.8.2)
     */
    public Signature sign(Hash hash, SigningPrivateKey signingKey) {
        return signIt(hash, signingKey);
    }

    /**
     *  @param hash either a Hash or a SHA1Hash
     *  @since 0.8.3
     */
    private Signature signIt(SimpleDataStructure hash, SigningPrivateKey signingKey) {
        if ((signingKey == null) || (hash == null)) return null;
        long start = _context.clock().now();

        Signature sig = new Signature();
        BigInteger k;

        boolean ok = false;
        do {
            k = new BigInteger(160, _context.random());
            ok = k.compareTo(CryptoConstants.dsaq) != 1;
            ok = ok && !k.equals(BigInteger.ZERO);
            //System.out.println("K picked (ok? " + ok + "): " + k.bitLength() + ": " + k.toString());
        } while (!ok);

        BigInteger r = CryptoConstants.dsag.modPow(k, CryptoConstants.dsap).mod(CryptoConstants.dsaq);
        BigInteger kinv = k.modInverse(CryptoConstants.dsaq);

        BigInteger M = new NativeBigInteger(1, hash.getData());
        BigInteger x = new NativeBigInteger(1, signingKey.getData());
        BigInteger s = (kinv.multiply(M.add(x.multiply(r)))).mod(CryptoConstants.dsaq);

        byte[] rbytes = r.toByteArray();
        byte[] sbytes = s.toByteArray();
        byte[] out = new byte[40];

        // (q^random)%p is computationally random
        _context.random().harvester().feedEntropy("DSA.sign", rbytes, 0, rbytes.length);
        
        if (rbytes.length == 20) {
            //System.arraycopy(rbytes, 0, out, 0, 20);
            for (int i = 0; i < 20; i++) {
                out[i] = rbytes[i];
            }
        } else if (rbytes.length == 21) {
            //System.arraycopy(rbytes, 1, out, 0, 20);
            for (int i = 0; i < 20; i++) {
                out[i] = rbytes[i + 1];
            }
        } else {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Using short rbytes.length [" + rbytes.length + "]");
            //System.arraycopy(rbytes, 0, out, 20 - rbytes.length, rbytes.length);
            for (int i = 0; i < rbytes.length; i++)
                out[i + 20 - rbytes.length] = rbytes[i];
        }
        if (sbytes.length == 20) {
            //System.arraycopy(sbytes, 0, out, 20, 20);
            for (int i = 0; i < 20; i++) {
                out[i + 20] = sbytes[i];
            }
        } else if (sbytes.length == 21) {
            //System.arraycopy(sbytes, 1, out, 20, 20);
            for (int i = 0; i < 20; i++) {
                out[i + 20] = sbytes[i + 1];
            }
        } else {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Using short sbytes.length [" + sbytes.length + "]");
            //System.arraycopy(sbytes, 0, out, 40 - sbytes.length, sbytes.length);
            for (int i = 0; i < sbytes.length; i++)
                out[i + 20 + 20 - sbytes.length] = sbytes[i];
        }
        sig.setData(out);

        long diff = _context.clock().now() - start;
        if (diff > 1000) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Took too long to sign (" + diff + "ms)");
        }

        return sig;
    }
    
    /**
     *  Reads the stream until EOF. Does not close the stream.
     *
     *  @return hash SHA-1 hash, NOT a SHA-256 hash
     */
    public SHA1Hash calculateHash(InputStream in) {
        MessageDigest digest = SHA1.getInstance();
        byte buf[] = new byte[64];
        int read = 0;
        try {
            while ( (read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to hash the stream", ioe);
            return null;
        }
        return new SHA1Hash(digest.digest());
    }

    /** @return hash SHA-1 hash, NOT a SHA-256 hash */
    public static SHA1Hash calculateHash(byte[] source, int offset, int len) {
        MessageDigest h = SHA1.getInstance();
        h.update(source, offset, len);
        byte digested[] = h.digest();
        return new SHA1Hash(digested);
    }

    /**
     *  Alternate to verifySignature() using java.security libraries.
     *  @throws GeneralSecurityException if algorithm unvailable or on other errors
     *  @since 0.8.7
     */
    private boolean altVerifySigSHA1(Signature signature, byte[] data, SigningPublicKey verifyingKey) throws GeneralSecurityException {
        java.security.Signature jsig = java.security.Signature.getInstance("SHA1withDSA");
        KeyFactory keyFact = KeyFactory.getInstance("DSA");
        // y p q g
        KeySpec spec = new DSAPublicKeySpec(new NativeBigInteger(1, verifyingKey.getData()),
                                            CryptoConstants.dsap,
                                            CryptoConstants.dsaq,
                                            CryptoConstants.dsag);
        PublicKey pubKey = keyFact.generatePublic(spec);
        jsig.initVerify(pubKey);
        jsig.update(data);
        boolean rv = jsig.verify(sigBytesToASN1(signature.getData()));
        //if (!rv) {
        //    System.out.println("BAD SIG\n" + net.i2p.util.HexDump.dump(signature.getData()));
        //    System.out.println("BAD SIG\n" + net.i2p.util.HexDump.dump(sigBytesToASN1(signature.getData())));
        //}
        return rv;
    }

    /**
     *  Alternate to sign() using java.security libraries.
     *  @throws GeneralSecurityException if algorithm unvailable or on other errors
     *  @since 0.8.7
     */
    private Signature altSignSHA1(byte[] data, SigningPrivateKey privateKey) throws GeneralSecurityException {
        java.security.Signature jsig = java.security.Signature.getInstance("SHA1withDSA");
        KeyFactory keyFact = KeyFactory.getInstance("DSA");
        // y p q g
        KeySpec spec = new DSAPrivateKeySpec(new NativeBigInteger(1, privateKey.getData()),
                                            CryptoConstants.dsap,
                                            CryptoConstants.dsaq,
                                            CryptoConstants.dsag);
        PrivateKey privKey = keyFact.generatePrivate(spec);
        jsig.initSign(privKey, _context.random());
        jsig.update(data);
        return new Signature(aSN1ToSigBytes(jsig.sign()));
    }

    /**
     *  http://download.oracle.com/javase/1.5.0/docs/guide/security/CryptoSpec.html
     *  Signature Format	ASN.1 sequence of two INTEGER values: r and s, in that order:
     *                                SEQUENCE ::= { r INTEGER, s INTEGER }
     *
     *  http://en.wikipedia.org/wiki/Abstract_Syntax_Notation_One
     *  30 -- tag indicating SEQUENCE
     *  xx - length in octets
     *
     *  02 -- tag indicating INTEGER
     *  xx - length in octets
     *  xxxxxx - value
     *
     *  Convert to BigInteger and back so we have the minimum length representation, as required.
     *  r and s are always non-negative.
     *
     *  @since 0.8.7
     */
    private static byte[] sigBytesToASN1(byte[] sig) {
        //System.out.println("pre TO asn1\n" + net.i2p.util.HexDump.dump(sig));
        ByteArrayOutputStream baos = new ByteArrayOutputStream(48);
        baos.write(0x30);
        baos.write(0);  // length to be filled in below

        byte[] tmp = new byte[20];
        baos.write(2);
        System.arraycopy(sig, 0, tmp, 0, 20);
        BigInteger r = new BigInteger(1, tmp);
        byte[] b = r.toByteArray();
        baos.write(b.length);
        baos.write(b, 0, b.length);

        baos.write(2);
        System.arraycopy(sig, 20, tmp, 0, 20);
        BigInteger s = new BigInteger(1, tmp);
        b = s.toByteArray();
        baos.write(b.length);
        baos.write(b, 0, b.length);
        byte[] rv = baos.toByteArray();
        rv[1] = (byte) (rv.length - 2);
        //System.out.println("post TO asn1\n" + net.i2p.util.HexDump.dump(rv));
        return rv;
    }

    /**
     *  See above.
     *  @since 0.8.7
     */
    private static byte[] aSN1ToSigBytes(byte[] asn) {
        //System.out.println("pre from asn1\n" + net.i2p.util.HexDump.dump(asn));
        byte[] rv = new byte[40];
        int rlen = asn[3];
        if ((asn[4] & 0x80) != 0)
            throw new IllegalArgumentException("R is negative");
        if (rlen > 21)
            throw new IllegalArgumentException("R too big " + rlen);
        else if (rlen == 21) {
            System.arraycopy(asn, 5, rv, 0, 20);
        } else
            System.arraycopy(asn, 4, rv, 20 - rlen, rlen);
        int slenloc = 25 + rlen - 20;
        int slen = asn[slenloc];
        if ((asn[slenloc + 1] & 0x80) != 0)
            throw new IllegalArgumentException("S is negative");
        if (slen > 21)
            throw new IllegalArgumentException("S too big " + slen);
        else if (slen == 21) {
            System.arraycopy(asn, slenloc + 2, rv, 20, 20);
        } else
            System.arraycopy(asn, slenloc + 1, rv, 40 - slen, slen);
        //System.out.println("post from asn1\n" + net.i2p.util.HexDump.dump(rv));
        return rv;
    }

    private static final int RUNS = 1000;

    /**
     *  Run consistency and speed tests with both TheCrypto and java.security libraries.
     *
     *  TheCrypto is about 5-15% faster than java.security.
     */
    public static void main(String args[]) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        byte data[] = new byte[1024];
        // warmump
        ctx.random().nextBytes(data);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {}
        SimpleDataStructure keys[] = null;

        System.err.println("100 runs with new data and keys each time");
        for (int i = 0; i < 100; i++) {
            ctx.random().nextBytes(data);
            keys = ctx.keyGenerator().generateSigningKeys();
            Signature sig = ctx.dsa().sign(data, (SigningPrivateKey)keys[1]);
            Signature jsig = null;
            try {
                 jsig = ctx.dsa().altSignSHA1(data, (SigningPrivateKey)keys[1]);
            } catch (GeneralSecurityException gse) {
                gse.printStackTrace();
            }
            boolean ok = ctx.dsa().verifySignature(jsig, data, (SigningPublicKey)keys[0]);
            boolean usok = ctx.dsa().verifySignature(sig, data, (SigningPublicKey)keys[0]);
            boolean jok = false;
            try {
                jok = ctx.dsa().altVerifySigSHA1(sig, data, (SigningPublicKey)keys[0]);
            } catch (GeneralSecurityException gse) {
                gse.printStackTrace();
            }
            boolean jjok = false;;
            try {
                jjok = ctx.dsa().altVerifySigSHA1(jsig, data, (SigningPublicKey)keys[0]);
            } catch (GeneralSecurityException gse) {
                gse.printStackTrace();
            }
            System.err.println("TC->TC OK: " + usok + "  JL->TC OK: " + ok + "  TC->JK OK: " + jok + "  JL->JL OK: " + jjok);
            if (!(ok && usok && jok && jjok)) {
                System.out.println("privkey\n" + net.i2p.util.HexDump.dump(keys[1].getData()));
                return;
            }
        }

        System.err.println("Starting speed test");
        long start = System.currentTimeMillis();
        for (int i = 0; i < RUNS; i++) {
            Signature sig = ctx.dsa().sign(data, (SigningPrivateKey)keys[1]);
            boolean ok = ctx.dsa().verifySignature(sig, data, (SigningPublicKey)keys[0]);
            if (!ok) {
                System.err.println("TheCrypto FAIL");
                return;
            }
        }
        long time = System.currentTimeMillis() - start;
        System.err.println("Time for " + RUNS + " DSA sign/verifies:");
        System.err.println("TheCrypto time (ms): " + time);

        start = System.currentTimeMillis();
        for (int i = 0; i < RUNS; i++) {
            boolean ok = false;
            try {
                Signature jsig = ctx.dsa().altSignSHA1(data, (SigningPrivateKey)keys[1]);
                ok = ctx.dsa().altVerifySigSHA1(jsig, data, (SigningPublicKey)keys[0]);
            } catch (GeneralSecurityException gse) {
                gse.printStackTrace();
            }
            if (!ok) {
                System.err.println("JavaLib FAIL");
                return;
            }
        }
        time = System.currentTimeMillis() - start;
        System.err.println("JavaLib time (ms): " + time);

/****  yes, arraycopy is slower for 20 bytes
        start = System.currentTimeMillis();
	byte b[] = new byte[20];
        for (int i = 0; i < 10000000; i++) {
            data[0] = data[i % 256];
            System.arraycopy(data, 0, b, 0, 20);
        }
        time = System.currentTimeMillis() - start;
        System.err.println("arraycopy time (ms): " + time);

        start = System.currentTimeMillis();
        for (int i = 0; i < 10000000; i++) {
            data[0] = data[i % 256];
            for (int j = 0; j < 20; j++) {
                 b[j] = data[j];
            }
        }
        time = System.currentTimeMillis() - start;
        System.err.println("loop time (ms): " + time);
****/
     }
}
