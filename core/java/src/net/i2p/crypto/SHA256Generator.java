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

import net.i2p.data.Hash;
import net.i2p.I2PAppContext;

/** Defines a wrapper for SHA-256 operation
 *
 * This is done. Takes data of any size and hashes it. 
 * 
 * @author thecrypto,jrandom
 */
public class SHA256Generator {
    public SHA256Generator(I2PAppContext context) {};
    public static SHA256Generator getInstance() {
        return I2PAppContext.getGlobalContext().sha();
    }

    static int[] K = { 0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
                      0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
                      0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
                      0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
                      0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
                      0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
                      0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
                      0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

    static int[] H0 = { 0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};

    /** Calculate the SHA-256 has of the source
     * @param source what to hash
     * @return hash of the source
     */
    public Hash calculateHash(byte[] source) {
        long length = source.length * 8;
        int k = 448 - (int) ((length + 1) % 512);
        if (k < 0) {
            k += 512;
        }
        int padbytes = k / 8;
        int wordlength = (int) (source.length / 4 + padbytes / 4 + 3);
        int[] M0 = new int[wordlength];
        int wordcount = 0;
        int x = 0;
        for (x = 0; x < (source.length / 4) * 4; x += 4) {
            M0[wordcount] = source[x] << 24 >>> 24 << 24;
            M0[wordcount] |= source[x + 1] << 24 >>> 24 << 16;
            M0[wordcount] |= source[x + 2] << 24 >>> 24 << 8;
            M0[wordcount] |= source[x + 3] << 24 >>> 24 << 0;
            wordcount++;
        }
        switch (source.length - (wordcount + 1) * 4 + 4) {
        case 0:
            M0[wordcount] |= 0x80000000;
            break;
        case 1:
            M0[wordcount] = source[x] << 24 >>> 24 << 24;
            M0[wordcount] |= 0x00800000;
            break;
        case 2:
            M0[wordcount] = source[x] << 24 >>> 24 << 24;
            M0[wordcount] |= source[x + 1] << 24 >>> 24 << 16;
            M0[wordcount] |= 0x00008000;
            break;
        case 3:
            M0[wordcount] = source[x] << 24 >>> 24 << 24;
            M0[wordcount] |= source[x + 1] << 24 >>> 24 << 16;
            M0[wordcount] |= source[x + 2] << 24 >>> 24 << 8;
            M0[wordcount] |= 0x00000080;
            break;
        }
        M0[wordlength - 2] = (int) (length >>> 32);
        M0[wordlength - 1] = (int) (length);
        int[] H = new int[8];
        for (x = 0; x < 8; x++) {
            H[x] = H0[x];
        }
        int blocks = M0.length / 16;
        for (int bl = 0; bl < blocks; bl++) {
            int a = H[0];
            int b = H[1];
            int c = H[2];
            int d = H[3];
            int e = H[4];
            int f = H[5];
            int g = H[6];
            int h = H[7];
            int[] W = new int[64];
            for (x = 0; x < 64; x++) {
                if (x < 16) {
                    W[x] = M0[bl * 16 + x];
                } else {
                    W[x] = add(o1(W[x - 2]), add(W[x - 7], add(o0(W[x - 15]), W[x - 16])));
                }
            }
            for (x = 0; x < 64; x++) {
                int T1 = add(h, add(e1(e), add(Ch(e, f, g), add(K[x], W[x]))));
                int T2 = add(e0(a), Maj(a, b, c));
                h = g;
                g = f;
                f = e;
                e = add(d, T1);
                d = c;
                c = b;
                b = a;
                a = add(T1, T2);
            }
            H[0] = add(a, H[0]);
            H[1] = add(b, H[1]);
            H[2] = add(c, H[2]);
            H[3] = add(d, H[3]);
            H[4] = add(e, H[4]);
            H[5] = add(f, H[5]);
            H[6] = add(g, H[6]);
            H[7] = add(h, H[7]);
        }
        byte[] hashbytes = new byte[32];
        for (x = 0; x < 8; x++) {
            hashbytes[x * 4] = (byte) (H[x] << 0 >>> 24);
            hashbytes[x * 4 + 1] = (byte) (H[x] << 8 >>> 24);
            hashbytes[x * 4 + 2] = (byte) (H[x] << 16 >>> 24);
            hashbytes[x * 4 + 3] = (byte) (H[x] << 24 >>> 24);
        }
        Hash hash = new Hash();
        hash.setData(hashbytes);
        return hash;
    }

    private static int Ch(int x, int y, int z) {
        return (x & y) ^ (~x & z);
    }

    private static int Maj(int x, int y, int z) {
        return (x & y) ^ (x & z) ^ (y & z);
    }

    private static int ROTR(int x, int n) {
        return (x >>> n) | (x << 32 - n);
    }

    private static int e0(int x) {
        return ROTR(x, 2) ^ ROTR(x, 13) ^ ROTR(x, 22);
    }

    private static int e1(int x) {
        return ROTR(x, 6) ^ ROTR(x, 11) ^ ROTR(x, 25);
    }

    private static int SHR(int x, int n) {
        return x >>> n;
    }

    private static int o0(int x) {
        return ROTR(x, 7) ^ ROTR(x, 18) ^ SHR(x, 3);
    }

    private static int o1(int x) {
        return ROTR(x, 17) ^ ROTR(x, 19) ^ SHR(x, 10);
    }

    private static int add(int x, int y) {
        return x + y;
    }
}