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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.DSAKey;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;

import net.i2p.I2PAppContext;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAKey;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;

/**
 *  Sign and verify using DSA-SHA1 and other signature algorithms.
 *  Also contains methods to sign and verify using a SHA-256 Hash.
 *
 *  The primary implementation is code from TheCryto.
 *  As of 0.8.7, also included is an alternate implementation using java.security libraries, which
 *  is slightly slower. This implementation could in the future be easily modified
 *  to use a new signing algorithm from java.security when we change the signing algorithm.
 *
 *  Params and rv's changed from Hash to SHA1Hash for version 0.8.1
 *  Hash variants of sign() and verifySignature() restored in 0.8.3, required by Syndie.
 *
 *  As of 0.9.9, certain methods support RSA and ECDSA keys and signatures, i.e. all types
 *  specified in SigType. The type is specified by the getType() method in
 *  Signature, SigningPublicKey, and SigningPrivateKey. See Javadocs for individual
 *  methods for the supported types. Methods encountering an unsupported type
 *  will throw an IllegalArgumentException.
 *
 *  EdDSA support added in 0.9.15
 */
public final class DSAEngine {
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
     *  Verify using any sig type.
     *  Uses TheCrypto code for DSA-SHA1 unless configured to use the java.security libraries.
     */
    public boolean verifySignature(Signature signature, byte signedData[], SigningPublicKey verifyingKey) {
        return verifySignature(signature, signedData, 0, signedData.length, verifyingKey);
    }

    /**
     *  Verify using any sig type as of 0.9.12 (DSA only prior to that)
     */
    public boolean verifySignature(Signature signature, byte signedData[], int offset, int size, SigningPublicKey verifyingKey) {
        boolean rv;
        SigType type = signature.getType();
        if (type != verifyingKey.getType())
            throw new IllegalArgumentException("type mismatch sig=" + signature.getType() + " key=" + verifyingKey.getType());
        if (type != SigType.DSA_SHA1) {
            try {
                rv = altVerifySig(signature, signedData, offset, size, verifyingKey);
                if ((!rv) && _log.shouldLog(Log.WARN))
                    _log.warn(type + " Sig Verify Fail");
                return rv;
            } catch (GeneralSecurityException gse) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(type + " Sig Verify Fail", gse);
                return false;
            }
        }
        if (_useJavaLibs) {
            try {
                rv = altVerifySigSHA1(signature, signedData, offset, size, verifyingKey);
                if ((!rv) && _log.shouldLog(Log.WARN))
                    _log.warn("Lib DSA Sig Verify Fail");
                return rv;
            } catch (GeneralSecurityException gse) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Lib DSA Sig Verify Fail");
                // now try TheCrypto
            }
        }
        rv = verifySignature(signature, calculateHash(signedData, offset, size), verifyingKey);
        if ((!rv) && _log.shouldLog(Log.WARN))
            _log.warn("TheCrypto DSA Sig Verify Fail");
        return rv;
    }

    /**
     *  Verify using DSA-SHA1 ONLY
     */
    public boolean verifySignature(Signature signature, InputStream in, SigningPublicKey verifyingKey) {
        return verifySignature(signature, calculateHash(in), verifyingKey);
    }

    /**
     *  Verify using DSA-SHA1 ONLY
     *  @param hash SHA-1 hash, NOT a SHA-256 hash
     */
    public boolean verifySignature(Signature signature, SHA1Hash hash, SigningPublicKey verifyingKey) {
        return verifySig(signature, hash, verifyingKey);
    }

    /**
     *  Nonstandard.
     *  Used by Syndie.
     *  @since 0.8.3 (restored, was removed in 0.8.1 and 0.8.2)
     */
    public boolean verifySignature(Signature signature, Hash hash, SigningPublicKey verifyingKey) {
        return verifySig(signature, hash, verifyingKey);
    }

    /**
     *  Generic signature type.
     *
     *  Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     *  @param hash SHA1Hash, Hash, Hash384, or Hash512
     *  @since 0.9.9
     */
    public boolean verifySignature(Signature signature, SimpleDataStructure hash, SigningPublicKey verifyingKey) {
        SigType type = signature.getType();
        if (type != verifyingKey.getType())
            throw new IllegalArgumentException("type mismatch sig=" + type + " key=" + verifyingKey.getType());
        int hashlen = type.getHashLen();
        if (hash.length() != hashlen)
            throw new IllegalArgumentException("type mismatch hash=" + hash.getClass() + " sig=" + type);
        if (type == SigType.DSA_SHA1)
            return verifySig(signature, hash, verifyingKey);
        try {
            return altVerifySigRaw(signature, hash, verifyingKey);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(type + " Sig Verify Fail", gse);
            return false;
        }
    }

    /**
     *  Generic signature type.
     *  If you have a Java pubkey, use this, so you don't lose the key parameters,
     *  which may be different than the ones defined in SigType.
     *
     *  Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     *  @param hash SHA1Hash, Hash, Hash384, or Hash512
     *  @param pubKey Java key
     *  @since 0.9.9
     */
    public boolean verifySignature(Signature signature, SimpleDataStructure hash, PublicKey pubKey) {
        try {
            return altVerifySigRaw(signature, hash, pubKey);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(signature.getType() + " Sig Verify Fail", gse);
            return false;
        }
    }

    /**
     *  Verify using DSA-SHA1 or Syndie DSA-SHA256 ONLY.
     *  @param hash either a Hash or a SHA1Hash
     *  @since 0.8.3
     */
    private boolean verifySig(Signature signature, SimpleDataStructure hash, SigningPublicKey verifyingKey) {
        if (signature.getType() != SigType.DSA_SHA1)
            throw new IllegalArgumentException("Bad sig type " + signature.getType());
        if (verifyingKey.getType() != SigType.DSA_SHA1)
            throw new IllegalArgumentException("Bad key type " + verifyingKey.getType());
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
            BigInteger w;
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
        } catch (RuntimeException e) {
            _log.log(Log.CRIT, "Error verifying the signature", e);
            return false;
        }
    }

    /**
     *  Sign using any key type.
     *  Uses TheCrypto code unless configured to use the java.security libraries.
     *
     *  @return null on error
     */
    public Signature sign(byte data[], SigningPrivateKey signingKey) {
        return sign(data, 0, data.length, signingKey);
    }

    /**
     *  Sign using any key type as of 0.9.12 (DSA-SHA1 only prior to that)
     *
     *  @return null on error
     */
    public Signature sign(byte data[], int offset, int length, SigningPrivateKey signingKey) {
        if ((signingKey == null) || (data == null) || (data.length <= 0)) return null;
        SigType type = signingKey.getType();
        if (type != SigType.DSA_SHA1) {
            try {
                return altSign(data, offset, length, signingKey);
            } catch (GeneralSecurityException gse) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error(type + " Sign Fail", gse);
                return null;
            }
        }
        if (_useJavaLibs) {
            try {
                return altSignSHA1(data, offset, length, signingKey);
            } catch (GeneralSecurityException gse) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Lib Sign Fail, privkey = " + signingKey, gse);
                // now try TheCrypto
            }
        }
        SHA1Hash h = calculateHash(data, offset, length);
        return sign(h, signingKey);
    }
    
    /**
     *  Sign using DSA-SHA1 ONLY.
     *  Reads the stream until EOF. Does not close the stream.
     *
     *  @return null on error
     */
    public Signature sign(InputStream in, SigningPrivateKey signingKey) {
        if ((signingKey == null) || (in == null) ) return null;
        SHA1Hash h = calculateHash(in);
        return sign(h, signingKey);
    }

    /**
     *  Sign using DSA-SHA1 ONLY.
     *
     *  @param hash SHA-1 hash, NOT a SHA-256 hash
     *  @return null on error
     */
    public Signature sign(SHA1Hash hash, SigningPrivateKey signingKey) {
        return signIt(hash, signingKey);
    }

    /**
     *  Nonstandard.
     *  Used by Syndie.
     *
     *  @return null on error
     *  @since 0.8.3 (restored, was removed in 0.8.1 and 0.8.2)
     */
    public Signature sign(Hash hash, SigningPrivateKey signingKey) {
        return signIt(hash, signingKey);
    }

    /**
     *  Generic signature type.
     *
     *  Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     *  @param hash SHA1Hash, Hash, Hash384, or Hash512
     *  @return null on error
     *  @since 0.9.9
     */
    public Signature sign(SimpleDataStructure hash, SigningPrivateKey signingKey) {
        SigType type = signingKey.getType();
        int hashlen = type.getHashLen();
        if (hash.length() != hashlen)
            throw new IllegalArgumentException("type mismatch hash=" + hash.getClass() + " key=" + type);
        if (type == SigType.DSA_SHA1)
            return signIt(hash, signingKey);
        try {
            return altSignRaw(hash, signingKey);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(type + " Sign Fail", gse);
            return null;
        }
    }

    /**
     *  Generic signature type.
     *  If you have a Java privkey, use this, so you don't lose the key parameters,
     *  which may be different than the ones defined in SigType.
     *
     *  Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     *  @param hash SHA1Hash, Hash, Hash384, or Hash512
     *  @param privKey Java key
     *  @param type returns a Signature of this type
     *  @return null on error
     *  @since 0.9.9
     */
    public Signature sign(SimpleDataStructure hash, PrivateKey privKey, SigType type) {
        String algo = getRawAlgo(privKey);
        String talgo = getRawAlgo(type);
        if (!algo.equals(talgo))
            throw new IllegalArgumentException("type mismatch type=" + type + " key=" + privKey.getClass().getSimpleName());
        try {
            return altSignRaw(algo, hash, privKey, type);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(type + " Sign Fail", gse);
            return null;
        }
    }

    /**
     *  Sign using DSA-SHA1 or Syndie DSA-SHA256 ONLY.
     *
     *  @param hash either a Hash or a SHA1Hash
     *  @return null on error
     *  @since 0.8.3
     */
    private Signature signIt(SimpleDataStructure hash, SigningPrivateKey signingKey) {
        if ((signingKey == null) || (hash == null)) return null;
        if (signingKey.getType() != SigType.DSA_SHA1)
            throw new IllegalArgumentException("Bad key type " + signingKey.getType());
        long start = _context.clock().now();

        BigInteger k;
        boolean ok;
        do {
            k = new NativeBigInteger(160, _context.random());
            ok = k.compareTo(CryptoConstants.dsaq) != 1;
            ok = ok && !k.equals(BigInteger.ZERO);
            //System.out.println("K picked (ok? " + ok + "): " + k.bitLength() + ": " + k.toString());
        } while (!ok);

        BigInteger r = CryptoConstants.dsag.modPowCT(k, CryptoConstants.dsap).mod(CryptoConstants.dsaq);
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
        } else if (rbytes.length > 21) {
            _log.error("Bad R length " + rbytes.length);
            return null;
        } else {
            //if (_log.shouldLog(Log.DEBUG)) _log.debug("Using short rbytes.length [" + rbytes.length + "]");
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
        } else if (sbytes.length > 21) {
            _log.error("Bad S length " + sbytes.length);
            return null;
        } else {
            //if (_log.shouldLog(Log.DEBUG)) _log.debug("Using short sbytes.length [" + sbytes.length + "]");
            //System.arraycopy(sbytes, 0, out, 40 - sbytes.length, sbytes.length);
            for (int i = 0; i < sbytes.length; i++)
                out[i + 20 + 20 - sbytes.length] = sbytes[i];
        }

        long diff = _context.clock().now() - start;
        if (diff > 1000) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Took too long to sign (" + diff + "ms)");
        }

        return new Signature(out);
    }
    
    /**
     *  Reads the stream until EOF. Does not close the stream.
     *
     *  @return hash SHA-1 hash, NOT a SHA-256 hash
     *  @deprecated unused
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
     *  Generic verify any type.
     *
     *  @throws GeneralSecurityException if algorithm unvailable or on other errors
     *  @since 0.9.9 added off/len 0.9.12
     */
    private boolean altVerifySig(Signature signature, byte[] data, int offset, int len, SigningPublicKey verifyingKey)
                        throws GeneralSecurityException {
        SigType type = signature.getType();
        if (type != verifyingKey.getType())
            throw new IllegalArgumentException("type mismatch sig=" + type + " key=" + verifyingKey.getType());
        if (type == SigType.DSA_SHA1)
            return altVerifySigSHA1(signature, data, offset, len, verifyingKey);

        PublicKey pubKey = SigUtil.toJavaKey(verifyingKey);
        byte[] sigbytes = SigUtil.toJavaSig(signature);
        boolean rv;
        if (type.getBaseAlgorithm() == SigAlgo.EdDSA) {
            // take advantage of one-shot mode
            EdDSAEngine jsig = new EdDSAEngine(type.getDigestInstance());
            jsig.initVerify(pubKey);
            rv = jsig.verifyOneShot(data, offset, len, sigbytes);
        } else {
            java.security.Signature jsig = java.security.Signature.getInstance(type.getAlgorithmName());
            jsig.initVerify(pubKey);
            jsig.update(data, offset, len);
            rv = jsig.verify(sigbytes);
        }
        return rv;
    }

    /**
     *  Generic raw verify any type
     *
     *  Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     *  @throws GeneralSecurityException if algorithm unvailable or on other errors
     *  @since 0.9.9
     */
    private boolean altVerifySigRaw(Signature signature, SimpleDataStructure hash, SigningPublicKey verifyingKey)
                        throws GeneralSecurityException {
        SigType type = signature.getType();
        if (type != verifyingKey.getType())
            throw new IllegalArgumentException("type mismatch sig=" + type + " key=" + verifyingKey.getType());

        PublicKey pubKey = SigUtil.toJavaKey(verifyingKey);
        return verifySignature(signature, hash, pubKey);
    }

    /**
     *  Generic raw verify any type.
     *  If you have a Java pubkey, use this, so you don't lose the key parameters,
     *  which may be different than the ones defined in SigType.
     *
     *  Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     *  @throws GeneralSecurityException if algorithm unvailable or on other errors
     *  @param verifyingKey Java key
     *  @since 0.9.9
     */
    private boolean altVerifySigRaw(Signature signature, SimpleDataStructure hash, PublicKey pubKey)
                        throws GeneralSecurityException {
        SigType type = signature.getType();
        int hashlen = hash.length();
        if (type.getHashLen() != hashlen)
            throw new IllegalArgumentException("type mismatch hash=" + hash.getClass() + " key=" + type);

        byte[] sigbytes = SigUtil.toJavaSig(signature);
        boolean rv;
        if (type.getBaseAlgorithm() == SigAlgo.EdDSA) {
            // take advantage of one-shot mode
            // Ignore algo, EdDSAKey includes a hash specification.
            EdDSAEngine jsig = new EdDSAEngine();
            jsig.initVerify(pubKey);
            rv = jsig.verifyOneShot(hash.getData(), sigbytes);
        } else {
            String algo = getRawAlgo(type);
            java.security.Signature jsig = java.security.Signature.getInstance(algo);
            jsig.initVerify(pubKey);
            jsig.update(hash.getData());
            rv = jsig.verify(sigbytes);
        }
        return rv;
    }

    /**
     *  Alternate to verifySignature() using java.security libraries.
     *  @throws GeneralSecurityException if algorithm unvailable or on other errors
     *  @since 0.8.7 added off/len 0.9.12
     */
    private boolean altVerifySigSHA1(Signature signature, byte[] data, int offset,
                                     int len, SigningPublicKey verifyingKey) throws GeneralSecurityException {
        java.security.Signature jsig = java.security.Signature.getInstance("SHA1withDSA");
        PublicKey pubKey = SigUtil.toJavaDSAKey(verifyingKey);
        jsig.initVerify(pubKey);
        jsig.update(data, offset, len);
        boolean rv = jsig.verify(SigUtil.toJavaSig(signature));
        //if (!rv) {
        //    System.out.println("BAD SIG\n" + net.i2p.util.HexDump.dump(signature.getData()));
        //    System.out.println("BAD SIG\n" + net.i2p.util.HexDump.dump(sigBytesToASN1(signature.getData())));
        //}
        return rv;
    }

    /**
     *  Generic sign any type.
     *
     *  @throws GeneralSecurityException if algorithm unvailable or on other errors
     *  @since 0.9.9 added off/len 0.9.12
     */
    private Signature altSign(byte[] data, int offset, int len,
                              SigningPrivateKey privateKey) throws GeneralSecurityException {
        SigType type = privateKey.getType();
        if (type == SigType.DSA_SHA1)
            return altSignSHA1(data, offset, len, privateKey);

        PrivateKey privKey = SigUtil.toJavaKey(privateKey);
        byte[] sigbytes;
        if (type.getBaseAlgorithm() == SigAlgo.EdDSA) {
            // take advantage of one-shot mode
            EdDSAEngine jsig = new EdDSAEngine(type.getDigestInstance());
            jsig.initSign(privKey);
            sigbytes = jsig.signOneShot(data, offset, len);
        } else {
            java.security.Signature jsig = java.security.Signature.getInstance(type.getAlgorithmName());
            jsig.initSign(privKey, _context.random());
            jsig.update(data, offset, len);
            sigbytes = jsig.sign();
        }
        return SigUtil.fromJavaSig(sigbytes, type);
    }

    /**
     *  Generic raw sign any type.
     *
     *  Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     *  @param hash SHA1Hash, Hash, Hash384, or Hash512
     *  @throws GeneralSecurityException if algorithm unvailable or on other errors
     *  @since 0.9.9
     */
    private Signature altSignRaw(SimpleDataStructure hash, SigningPrivateKey privateKey) throws GeneralSecurityException {
        SigType type = privateKey.getType();
        String algo = getRawAlgo(type);
        PrivateKey privKey = SigUtil.toJavaKey(privateKey);
        return altSignRaw(algo, hash, privKey, type);
    }

    /**
     *  Generic raw sign any type.
     *
     *  Warning, nonstandard for EdDSA, double-hashes, not recommended.
     *
     *  @param hash SHA1Hash, Hash, Hash384, or Hash512
     *  @param type returns a Signature of this type
     *  @throws GeneralSecurityException if algorithm unvailable or on other errors
     *  @since 0.9.9
     */
    private Signature altSignRaw(String algo, SimpleDataStructure hash, PrivateKey privKey, SigType type)
                                 throws GeneralSecurityException {
        int hashlen = hash.length();
        if (type.getHashLen() != hashlen)
            throw new IllegalArgumentException("type mismatch hash=" + hash.getClass() + " key=" + type);

        byte[] sigbytes;
        if (type.getBaseAlgorithm() == SigAlgo.EdDSA) {
            // take advantage of one-shot mode
            // Ignore algo, EdDSAKey includes a hash specification.
            EdDSAEngine jsig = new EdDSAEngine();
            jsig.initSign(privKey);
            sigbytes = jsig.signOneShot(hash.getData());
        } else {
            java.security.Signature jsig = java.security.Signature.getInstance(algo);
            jsig.initSign(privKey, _context.random());
            jsig.update(hash.getData());
            sigbytes = jsig.sign();
        }
        return SigUtil.fromJavaSig(sigbytes, type);
    }

    /**
     *  Alternate to sign() using java.security libraries.
     *  @throws GeneralSecurityException if algorithm unvailable or on other errors
     *  @since 0.8.7 added off/len args 0.9.12
     */
    private Signature altSignSHA1(byte[] data, int offset, int len,
                                  SigningPrivateKey privateKey) throws GeneralSecurityException {
        java.security.Signature jsig = java.security.Signature.getInstance("SHA1withDSA");
        PrivateKey privKey = SigUtil.toJavaDSAKey(privateKey);
        jsig.initSign(privKey, _context.random());
        jsig.update(data, offset, len);
        return SigUtil.fromJavaSig(jsig.sign(), SigType.DSA_SHA1);
    }

    /** @since 0.9.9 */
    private static String getRawAlgo(SigType type) {
        switch (type.getBaseAlgorithm()) {
            case DSA:
                return "NONEwithDSA";
            case EC:
                return "NONEwithECDSA";
            case EdDSA:
                return "NONEwithEdDSA";
            case RSA:
                return "NONEwithRSA";
            default:
                throw new UnsupportedOperationException("Raw signatures unsupported for " + type);
        }
    }

    /** @since 0.9.9 */
    private static String getRawAlgo(Key key) {
        if (key instanceof DSAKey)
            return "NONEwithDSA";
        if (key instanceof ECKey)
            return "NONEwithECDSA";
        if (key instanceof EdDSAKey)
            return "NONEwithEdDSA";
        if (key instanceof RSAKey)
            return "NONEwithRSA";
        throw new UnsupportedOperationException("Raw signatures unsupported for " + key.getClass().getName());
    }

    //private static final int RUNS = 1000;

    /**
     *  Run consistency and speed tests with both TheCrypto and java.security libraries.
     *
     *  TheCrypto is about 5-15% faster than java.security.
     */
/****
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
****/
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
/****
     }
****/
}
