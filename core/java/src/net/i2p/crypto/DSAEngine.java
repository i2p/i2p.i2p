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
import java.util.Arrays;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;

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
            BigInteger w = s.modInverse(CryptoConstants.dsaq);
            byte data[] = calculateHash(signedData, offset, size).getData();
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
        long start = _context.clock().now();

        Signature sig = new Signature();
        BigInteger k;

        do {
            k = new BigInteger(160, _context.random());
        } while (k.compareTo(CryptoConstants.dsaq) != 1);

        BigInteger r = CryptoConstants.dsag.modPow(k, CryptoConstants.dsap).mod(CryptoConstants.dsaq);
        BigInteger kinv = k.modInverse(CryptoConstants.dsaq);
        Hash h = calculateHash(data, offset, length);

        if (h == null) return null;

        BigInteger M = new NativeBigInteger(1, h.getData());
        BigInteger x = new NativeBigInteger(1, signingKey.getData());
        BigInteger s = (kinv.multiply(M.add(x.multiply(r)))).mod(CryptoConstants.dsaq);

        byte[] rbytes = r.toByteArray();
        byte[] sbytes = s.toByteArray();
        byte[] out = new byte[40];

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

    private int[] H0 = { 0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0};

    private Hash calculateHash(byte[] source, int offset, int len) {
        long length = len * 8;
        int k = 448 - (int) ((length + 1) % 512);
        if (k < 0) {
            k += 512;
        }
        int padbytes = k / 8;
        int wordlength = len / 4 + padbytes / 4 + 3;
        int[] M0 = new int[wordlength];
        int wordcount = 0;
        int x = 0;
        for (x = 0; x < (len / 4) * 4; x += 4) {
            M0[wordcount] = source[offset + x] << 24 >>> 24 << 24;
            M0[wordcount] |= source[offset + x + 1] << 24 >>> 24 << 16;
            M0[wordcount] |= source[offset + x + 2] << 24 >>> 24 << 8;
            M0[wordcount] |= source[offset + x + 3] << 24 >>> 24 << 0;
            wordcount++;
        }

        switch (len - (wordcount + 1) * 4 + 4) {
        case 0:
            M0[wordcount] |= 0x80000000;
            break;
        case 1:
            M0[wordcount] = source[offset + x] << 24 >>> 24 << 24;
            M0[wordcount] |= 0x00800000;
            break;
        case 2:
            M0[wordcount] = source[offset + x] << 24 >>> 24 << 24;
            M0[wordcount] |= source[offset + x + 1] << 24 >>> 24 << 16;
            M0[wordcount] |= 0x00008000;
            break;
        case 3:
            M0[wordcount] = source[offset + x] << 24 >>> 24 << 24;
            M0[wordcount] |= source[offset + x + 1] << 24 >>> 24 << 16;
            M0[wordcount] |= source[offset + x + 2] << 24 >>> 24 << 8;
            M0[wordcount] |= 0x00000080;
            break;
        }
        M0[wordlength - 2] = (int) (length >>> 32);
        M0[wordlength - 1] = (int) (length);
        int[] H = new int[5];
        for (x = 0; x < 5; x++) {
            H[x] = H0[x];
        }
        int blocks = M0.length / 16;
        
        int[] W = new int[80];
        for (int bl = 0; bl < blocks; bl++) {
            int a = H[0];
            int b = H[1];
            int c = H[2];
            int d = H[3];
            int e = H[4];

            Arrays.fill(W, 0);
            
            for (x = 0; x < 80; x++) {
                if (x < 16) {
                    W[x] = M0[bl * 16 + x];
                } else {
                    W[x] = ROTL(1, W[x - 3] ^ W[x - 8] ^ W[x - 14] ^ W[x - 16]);
                }
            }

            for (x = 0; x < 80; x++) {
                int T = add(ROTL(5, a), add(f(x, b, c, d), add(e, add(k(x), W[x]))));
                e = d;
                d = c;
                c = ROTL(30, b);
                b = a;
                a = T;
            }

            H[0] = add(a, H[0]);
            H[1] = add(b, H[1]);
            H[2] = add(c, H[2]);
            H[3] = add(d, H[3]);
            H[4] = add(e, H[4]);
        }

        byte[] hashbytes = new byte[20];
        for (x = 0; x < 5; x++) {
            hashbytes[x * 4] = (byte) (H[x] << 0 >>> 24);
            hashbytes[x * 4 + 1] = (byte) (H[x] << 8 >>> 24);
            hashbytes[x * 4 + 2] = (byte) (H[x] << 16 >>> 24);
            hashbytes[x * 4 + 3] = (byte) (H[x] << 24 >>> 24);
        }
        Hash hash = new Hash();
        hash.setData(hashbytes);
        return hash;
    }

    private int k(int t) {
        if (t > -1 && t < 20) {
            return 0x5a827999;
        } else if (t > 19 && t < 40) {
            return 0x6ed9eba1;
        } else if (t > 39 && t < 60) {
            return 0x8f1bbcdc;
        } else if (t > 59 && t < 80) { return 0xca62c1d6; }
        return 0x00000000;
    }

    private int f(int t, int x, int y, int z) {
        if (t > -1 && t < 20) {
            return Ch(x, y, z);
        } else if (t > 19 && t < 40) {
            return Parity(x, y, z);
        } else if (t > 39 && t < 60) {
            return Maj(x, y, z);
        } else if (t > 59 && t < 80) { return Parity(x, y, z); }
        return 0x00000000;
    }

    private int Ch(int x, int y, int z) {
        return (x & y) ^ (~x & z);
    }

    private int Parity(int x, int y, int z) {
        return x ^ y ^ z;
    }

    private int Maj(int x, int y, int z) {
        return (x & y) ^ (x & z) ^ (y & z);
    }

    private int ROTL(int n, int x) {
        return (x << n) | (x >>> 32 - n);
    }

    private int add(int x, int y) {
        return x + y;
    }
}