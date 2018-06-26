package net.i2p.crypto;

/*
 *  As pulled from https://github.com/nahi/siphash-java-inline
 *  Last commit was https://github.com/nahi/siphash-java-inline/commit/5be5c84851a28f800fcac66ced658bdbd01f31ef
 *  2012-11-06
 * 
 * Copyright 2012  Hiroshi Nakamura <nahi@ruby-lang.org>
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * *WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

/**
 * SipHash implementation with hand inlining the SIPROUND.
 *
 * To know details about SipHash, see;
 * "a fast short-input PRF" https://www.131002.net/siphash/
 * 
 * SIPROUND is defined in siphash24.c that can be downloaded from the above
 * site.  Following license notice is subject to change based on the licensing
 * policy of siphash24.c.
 * 
 * I2P mods: add off/len version
 * 
 * For constant keys see net.i2p.util.SipHash
 * 
 * @since 0.9.5, Moved to net.i2p.crypto and public since 0.9.27
 */
public final class SipHashInline {

    /** @since 0.9.27 */
    private SipHashInline() {};

    /**
     *  @param k0 the first 8 bytes of the key
     *  @param k1 the last 8 bytes of the key
     */
    public static long hash24(long k0, long k1, byte[] data) {
        return hash24(k0, k1, data, 0, data.length);
    }

    /**
     *  @param k0 the first 8 bytes of the key
     *  @param k1 the last 8 bytes of the key
     */
    public static long hash24(long k0, long k1, byte[] data, int off, int len) {
        long v0 = 0x736f6d6570736575L ^ k0;
        long v1 = 0x646f72616e646f6dL ^ k1;
        long v2 = 0x6c7967656e657261L ^ k0;
        long v3 = 0x7465646279746573L ^ k1;
        long m;
        int last = off + (len / 8 * 8);
        int i = off;

        // processing 8 bytes blocks in data
        while (i < last) {
            // pack a block to long, as LE 8 bytes
            m = ((((long) data[i++]) & 0xff)      ) |
                ((((long) data[i++]) & 0xff) <<  8) |
                ((((long) data[i++]) & 0xff) << 16) |
                ((((long) data[i++]) & 0xff) << 24) |
                ((((long) data[i++]) & 0xff) << 32) |
                ((((long) data[i++]) & 0xff) << 40) |
                ((((long) data[i++]) & 0xff) << 48) |
                ((((long) data[i++]) & 0xff) << 56);
            // MSGROUND {
                v3 ^= m;

                /* SIPROUND wih hand reordering
                 *
                 * SIPROUND in siphash24.c:
                 *   A: v0 += v1;
                 *   B: v1=ROTL(v1,13);
                 *   C: v1 ^= v0;
                 *   D: v0=ROTL(v0,32);
                 *   E: v2 += v3;
                 *   F: v3=ROTL(v3,16);
                 *   G: v3 ^= v2;
                 *   H: v0 += v3;
                 *   I: v3=ROTL(v3,21);
                 *   J: v3 ^= v0;
                 *   K: v2 += v1;
                 *   L: v1=ROTL(v1,17);
                 *   M: v1 ^= v2;
                 *   N: v2=ROTL(v2,32);
                 *
                 * Each dependency:
                 *   B -> A
                 *   C -> A, B
                 *   D -> C
                 *   F -> E
                 *   G -> E, F
                 *   H -> D, G
                 *   I -> H
                 *   J -> H, I
                 *   K -> C, G
                 *   L -> K
                 *   M -> K, L
                 *   N -> M
                 *
                 * Dependency graph:
                 *   D -> C -> B -> A
                 *        G -> F -> E
                 *   J -> I -> H -> D, G
                 *   N -> M -> L -> K -> C, G
                 *
                 * Resulting parallel friendly execution order:
                 *   -> ABCDHIJ
                 *   -> EFGKLMN
                 */

                // SIPROUND {
                    v0 += v1;                    v2 += v3;
                    v1 = (v1 << 13) | v1 >>> 51; v3 = (v3 << 16) | v3 >>> 48;
                    v1 ^= v0;                    v3 ^= v2;
                    v0 = (v0 << 32) | v0 >>> 32; v2 += v1;
                    v0 += v3;                    v1 = (v1 << 17) | v1 >>> 47;
                    v3 = (v3 << 21) | v3 >>> 43; v1 ^= v2;
                    v3 ^= v0;                    v2 = (v2 << 32) | v2 >>> 32;
                // }
                // SIPROUND {
                    v0 += v1;                    v2 += v3;
                    v1 = (v1 << 13) | v1 >>> 51; v3 = (v3 << 16) | v3 >>> 48;
                    v1 ^= v0;                    v3 ^= v2;
                    v0 = (v0 << 32) | v0 >>> 32; v2 += v1;
                    v0 += v3;                    v1 = (v1 << 17) | v1 >>> 47;
                    v3 = (v3 << 21) | v3 >>> 43; v1 ^= v2;
                    v3 ^= v0;                    v2 = (v2 << 32) | v2 >>> 32;
                // }
                v0 ^= m;
            // }
        }

        // packing the last block to long, as LE 0-7 bytes + the length in the top byte
        m = 0;
        for (i = off + len - 1; i >= last; --i) {
            m <<= 8; m |= (long) (data[i] & 0xff);
        }
        m |= (long) len << 56;
        // MSGROUND {
            v3 ^= m;
            // SIPROUND {
                v0 += v1;                    v2 += v3;
                v1 = (v1 << 13) | v1 >>> 51; v3 = (v3 << 16) | v3 >>> 48;
                v1 ^= v0;                    v3 ^= v2;
                v0 = (v0 << 32) | v0 >>> 32; v2 += v1;
                v0 += v3;                    v1 = (v1 << 17) | v1 >>> 47;
                v3 = (v3 << 21) | v3 >>> 43; v1 ^= v2;
                v3 ^= v0;                    v2 = (v2 << 32) | v2 >>> 32;
            // }
            // SIPROUND {
                v0 += v1;                    v2 += v3;
                v1 = (v1 << 13) | v1 >>> 51; v3 = (v3 << 16) | v3 >>> 48;
                v1 ^= v0;                    v3 ^= v2;
                v0 = (v0 << 32) | v0 >>> 32; v2 += v1;
                v0 += v3;                    v1 = (v1 << 17) | v1 >>> 47;
                v3 = (v3 << 21) | v3 >>> 43; v1 ^= v2;
                v3 ^= v0;                    v2 = (v2 << 32) | v2 >>> 32;
            // }
            v0 ^= m;
        // }

        // finishing...
        v2 ^= 0xff;
        // SIPROUND {
            v0 += v1;                    v2 += v3;
            v1 = (v1 << 13) | v1 >>> 51; v3 = (v3 << 16) | v3 >>> 48;
            v1 ^= v0;                    v3 ^= v2;
            v0 = (v0 << 32) | v0 >>> 32; v2 += v1;
            v0 += v3;                    v1 = (v1 << 17) | v1 >>> 47;
            v3 = (v3 << 21) | v3 >>> 43; v1 ^= v2;
            v3 ^= v0;                    v2 = (v2 << 32) | v2 >>> 32;
        // }
        // SIPROUND {
            v0 += v1;                    v2 += v3;
            v1 = (v1 << 13) | v1 >>> 51; v3 = (v3 << 16) | v3 >>> 48;
            v1 ^= v0;                    v3 ^= v2;
            v0 = (v0 << 32) | v0 >>> 32; v2 += v1;
            v0 += v3;                    v1 = (v1 << 17) | v1 >>> 47;
            v3 = (v3 << 21) | v3 >>> 43; v1 ^= v2;
            v3 ^= v0;                    v2 = (v2 << 32) | v2 >>> 32;
        // }
        // SIPROUND {
            v0 += v1;                    v2 += v3;
            v1 = (v1 << 13) | v1 >>> 51; v3 = (v3 << 16) | v3 >>> 48;
            v1 ^= v0;                    v3 ^= v2;
            v0 = (v0 << 32) | v0 >>> 32; v2 += v1;
            v0 += v3;                    v1 = (v1 << 17) | v1 >>> 47;
            v3 = (v3 << 21) | v3 >>> 43; v1 ^= v2;
            v3 ^= v0;                    v2 = (v2 << 32) | v2 >>> 32;
        // }
        // SIPROUND {
            v0 += v1;                    v2 += v3;
            v1 = (v1 << 13) | v1 >>> 51; v3 = (v3 << 16) | v3 >>> 48;
            v1 ^= v0;                    v3 ^= v2;
            v0 = (v0 << 32) | v0 >>> 32; v2 += v1;
            v0 += v3;                    v1 = (v1 << 17) | v1 >>> 47;
            v3 = (v3 << 21) | v3 >>> 43; v1 ^= v2;
            v3 ^= v0;                    v2 = (v2 << 32) | v2 >>> 32;
        // }
        return v0 ^ v1 ^ v2 ^ v3;
    }

    /**
     *  Test vectors from https://www.131002.net/siphash/siphash.pdf
     */
/****
    public static void main(String[] args) {
        long k0 = 0x0706050403020100L;
        long k1 = 0x0f0e0d0c0b0a0908L;
        byte[] data = new byte[15];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        long result = hash24(k0, k1, data);
        long expect = 0xa129ca6149be45e5L;
        if (result == expect)
            System.out.println("PASS");
        else
            System.out.println("FAIL expect " + Long.toString(expect, 16) +
                               " got " + Long.toString(result, 16));
    }
****/
}
