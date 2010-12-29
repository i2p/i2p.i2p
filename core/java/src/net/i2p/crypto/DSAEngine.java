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

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;

/**
 *  Params and rv's changed from Hash to SHA1Hash for version 0.8.1
 *  Hash variants of sign() and verifySignature() restored in 0.8.3, required by Syndie.
 */
public class DSAEngine {
    private Log _log;
    private I2PAppContext _context;

    public DSAEngine(I2PAppContext context) {
        _log = context.logManager().getLog(DSAEngine.class);
        _context = context;
    }
    public static DSAEngine getInstance() {
        return I2PAppContext.getGlobalContext().dsa();
    }
    public boolean verifySignature(Signature signature, byte signedData[], SigningPublicKey verifyingKey) {
        return verifySignature(signature, signedData, 0, signedData.length, verifyingKey);
    }
    public boolean verifySignature(Signature signature, byte signedData[], int offset, int size, SigningPublicKey verifyingKey) {
        return verifySignature(signature, calculateHash(signedData, offset, size), verifyingKey);
    }
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

    public Signature sign(byte data[], SigningPrivateKey signingKey) {
        return sign(data, 0, data.length, signingKey);
    }
    public Signature sign(byte data[], int offset, int length, SigningPrivateKey signingKey) {
        if ((signingKey == null) || (data == null) || (data.length <= 0)) return null;
        SHA1Hash h = calculateHash(data, offset, length);
        return sign(h, signingKey);
    }
    
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
            for (int i = 0; i < 20; i++) {
                out[i] = rbytes[i];
            }
        } else if (rbytes.length == 21) {
            for (int i = 0; i < 20; i++) {
                out[i] = rbytes[i + 1];
            }
        } else {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Using short rbytes.length [" + rbytes.length + "]");
            for (int i = 0; i < rbytes.length; i++)
                out[i + 20 - rbytes.length] = rbytes[i];
        }
        if (sbytes.length == 20) {
            for (int i = 0; i < 20; i++) {
                out[i + 20] = sbytes[i];
            }
        } else if (sbytes.length == 21) {
            for (int i = 0; i < 20; i++) {
                out[i + 20] = sbytes[i + 1];
            }
        } else {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Using short sbytes.length [" + sbytes.length + "]");
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
    
    /** @return hash SHA-1 hash, NOT a SHA-256 hash */
    public SHA1Hash calculateHash(InputStream in) {
        SHA1 digest = new SHA1();
        byte buf[] = new byte[64];
        int read = 0;
        try {
            while ( (read = in.read(buf)) != -1) {
                digest.engineUpdate(buf, 0, read);
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to hash the stream", ioe);
            return null;
        }
        return new SHA1Hash(digest.engineDigest());
    }

    /** @return hash SHA-1 hash, NOT a SHA-256 hash */
    public static SHA1Hash calculateHash(byte[] source, int offset, int len) {
        SHA1 h = new SHA1();
        h.engineUpdate(source, offset, len);
        byte digested[] = h.digest();
        return new SHA1Hash(digested);
    }

    public static void main(String args[]) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        byte data[] = new byte[4096];
        ctx.random().nextBytes(data);
        Object keys[] = ctx.keyGenerator().generateSigningKeypair();
        try {
            for (int i = 0; i < 10; i++) {
                Signature sig = ctx.dsa().sign(data, (SigningPrivateKey)keys[1]);
                boolean ok = ctx.dsa().verifySignature(sig, data, (SigningPublicKey)keys[0]);
                System.out.println("OK: " + ok);
            }
        } catch (Exception e) { e.printStackTrace(); }
        ctx.random().saveSeed();
    }
}
