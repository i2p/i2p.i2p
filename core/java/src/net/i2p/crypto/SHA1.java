package net.i2p.crypto;
/* @(#)SHA1.java	1.11 2004-04-26
 * This file was freely contributed to the LimeWire project and is covered
 * by its existing GPL licence, but it may be used individually as a public
 * domain implementation of a published algorithm (see below for references).
 * It was also freely contributed to the Bitzi public domain sources.
 * @author  Philippe Verdy
 */
 
/* Sun may wish to change the following package name, if integrating this
 * class in the Sun JCE Security Provider for Java 1.5 (code-named Tiger).
 *
 * You can include it in your own Security Provider by inserting
 * this property in your Provider derived class:
 * put("MessageDigest.SHA-1", "com.bitzi.util.SHA1");
 */
//package com.bitzi.util;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * NOTE: As of 0.8.7, use getInstance() instead of new SHA1(), which will
 * return the JVM's MessageDigest if it is faster.
 *
 * <p>The FIPS PUB 180-2 standard specifies four secure hash algorithms (SHA-1,
 * SHA-256, SHA-384 and SHA-512) for computing a condensed representation of
 * electronic data (message).  When a message of any length < 2^^64 bits (for
 * SHA-1 and SHA-256) or < 2^^128 bits (for SHA-384 and SHA-512) is input to
 * an algorithm, the result is an output called a message digest.  The message
 * digests range in length from 160 to 512 bits, depending on the algorithm.
 * Secure hash algorithms are typically used with other cryptographic
 * algorithms, such as digital signature algorithms and keyed-hash message
 * authentication codes, or in the generation of random numbers (bits).</p>
 *
 * <p>The four hash algorithms specified in this "SHS" standard are called
 * secure because, for a given algorithm, it is computationally infeasible
 * 1) to find a message that corresponds to a given message digest, or 2)
 * to find two different messages that produce the same message digest.  Any
 * change to a message will, with a very high probability, result in a
 * different message digest.  This will result in a verification failure when
 * the secure hash algorithm is used with a digital signature algorithm or a
 * keyed-hash message authentication algorithm.</p>
 *
 * <p>A "SHS change notice" adds a SHA-224 algorithm for interoperability,
 * which, like SHA-1 and SHA-256, operates on 512-bit blocks and 32-bit words,
 * but truncates the final digest and uses distinct initialization values.</p>
 *
 * <p><b>References:</b></p>
 * <ol>
 *   <li> NIST FIPS PUB 180-2, "Secure Hash Signature Standard (SHS) with
 *      change notice", National Institute of Standards and Technology (NIST),
 *      2002 August 1, and U.S. Department of Commerce, August 26.<br>
 *      <a href="http://csrc.ncsl.nist.gov/CryptoToolkit/Hash.html">
 *      http://csrc.ncsl.nist.gov/CryptoToolkit/Hash.html</a>
 *   <li> NIST FIPS PUB 180-1, "Secure Hash Standard",
 *      U.S. Department of Commerce, May 1993.<br>
 *      <a href="http://www.itl.nist.gov/div897/pubs/fip180-1.htm">
 *      http://www.itl.nist.gov/div897/pubs/fip180-1.htm</a></li>
 *   <li> Bruce Schneier, "Section 18.7 Secure Hash Algorithm (SHA)",
 *      <cite>Applied Cryptography, 2nd edition</cite>, <br>
 *      John Wiley & Sons, 1996</li>
 * </ol>
 */
public final class SHA1 extends MessageDigest implements Cloneable {
 
    /**
     * This implementation returns a fixed-size digest.
     */
    static final int HASH_LENGTH = 20; // bytes == 160 bits
 
    /**
     * Private context for incomplete blocks and padding bytes.
     * INVARIANT: padding must be in 0..63.
     * When the padding reaches 64, a new block is computed, and
     * the 56 last bytes are kept in the padding history.
     */
    private byte[] pad;
    private int padding;
 
    /**
     * Private contextual byte count, sent in the next block,
     * after the ending padding block.
     */
    private long bytes;
 
    /**
     * Private context that contains the current digest key.
     */
    private int hA, hB, hC, hD, hE;
 
    private static final boolean _useBitzi;
    static {
        // oddly, Bitzi is faster than Oracle - see test results below
        boolean useBitzi = true;
        String vendor = System.getProperty("java.vendor");
        if (vendor.startsWith("Apache") ||                      // Harmony
            vendor.startsWith("GNU Classpath") ||               // JamVM
            vendor.startsWith("Free Software Foundation")) {    // gij
            try {
                MessageDigest.getInstance("SHA-1");
                useBitzi = false;
            } catch (NoSuchAlgorithmException e) {}
        }
        //if (useBitzi)
        //    System.out.println("INFO: Using Bitzi SHA-1");
        _useBitzi = useBitzi;
    }

    /**
     * Creates a SHA1 object with default initial state.
     * NOTE: Use getInstance() to get the fastest implementation.
     */
    public SHA1() {
        super("SHA-1");
        pad = new byte[64];
        init();
    }
 
    /**
     *  @return the fastest digest, either new SHA1() or MessageDigest.getInstance("SHA-1")
     *  @since 0.8.7
     */
    public static MessageDigest getInstance() {
        if (!_useBitzi) {
            try {
                return MessageDigest.getInstance("SHA-1");
            } catch (NoSuchAlgorithmException e) {}
        }
        return new SHA1();
    }

    /**
     * Clones this object.
     */
    @Override
    public Object clone() throws CloneNotSupportedException {
        SHA1 that = (SHA1)super.clone();
        that.pad = this.pad.clone();
        return that;
    }
 
    /**
     * Returns the digest length in bytes.
     *
     * Can be used to allocate your own output buffer when
     * computing multiple digests.
     *
     * Overrides the protected abstract method of
     * <code>java.security.MessageDigestSpi</code>.
     * @return the digest length in bytes.
     */
    @Override
    public int engineGetDigestLength() {
        return HASH_LENGTH;
    }
 
    /**
     * Reset athen initialize the digest context.
     *
     * Overrides the protected abstract method of
     * <code>java.security.MessageDigestSpi</code>.
     */
    @Override
    protected void engineReset() {
        int i = 60;
        do {
           pad[i    ] = (byte)0x00;
           pad[i + 1] = (byte)0x00;
           pad[i + 2] = (byte)0x00;
           pad[i + 3] = (byte)0x00;
        } while ((i -= 4) >= 0);
        padding = 0;
        bytes = 0;
        init();
    }
 
    /**
     * Initialize the digest context.
     */
    protected void init() {
        hA = 0x67452301;
        hB = 0xefcdab89;
        hC = 0x98badcfe;
        hD = 0x10325476;
        hE = 0xc3d2e1f0;
    }
 
    /**
     * Updates the digest using the specified byte.
     * Requires internal buffering, and may be slow.
     *
     * Overrides the protected abstract method of
     * java.security.MessageDigestSpi.
     * @param input  the byte to use for the update.
     */
    public void engineUpdate(byte input) {
        bytes++;
        if (padding < 63) {
            pad[padding++] = input;
            return;
        }
        pad[63] = input;
        computeBlock(pad, 0);
        padding = 0;
    }
 
    /**
     * Updates the digest using the specified array of bytes,
     * starting at the specified offset.
     *
     * Input length can be any size. May require internal buffering,
     * if input blocks are not multiple of 64 bytes.
     *
     * Overrides the protected abstract method of
     * java.security.MessageDigestSpi.
     * @param input  the array of bytes to use for the update.
     * @param offset  the offset to start from in the array of bytes.
     * @param len  the number of bytes to use, starting at offset.
     */
    @Override
    public void engineUpdate(byte[] input, int offset, int len) {
        if (offset >= 0 && len >= 0 && offset + len <= input.length) {
            bytes += len;
            /* Terminate the previous block. */
            int padlen = 64 - padding;
            if (padding > 0 && len >= padlen) {
                System.arraycopy(input, offset, pad, padding, padlen);
                computeBlock(pad, 0);
                padding = 0;
                offset += padlen;
                len -= padlen;
            }
            /* Loop on large sets of complete blocks. */
            while (len >= 512) {
                computeBlock(input, offset);
                computeBlock(input, offset + 64);
                computeBlock(input, offset + 128);
                computeBlock(input, offset + 192);
                computeBlock(input, offset + 256);
                computeBlock(input, offset + 320);
                computeBlock(input, offset + 384);
                computeBlock(input, offset + 448);
                offset += 512;
                len -= 512;
            }
            /* Loop on remaining complete blocks. */
            while (len >= 64) {
                computeBlock(input, offset);
                offset += 64;
                len -= 64;
            }
            /* remaining bytes kept for next block. */
            if (len > 0) {
                System.arraycopy(input, offset, pad, padding, len);
                padding += len;
            }
            return;
        }
        throw new ArrayIndexOutOfBoundsException(offset);
    }
 
    /**
     * Completes the hash computation by performing final operations
     * such as padding. Computes the final hash and returns the final
     * value as a byte[20] array. Once engineDigest has been called,
     * the engine will be automatically reset as specified in the
     * JavaSecurity MessageDigest specification.
     *
     * For faster operations with multiple digests, allocate your own
     * array and use engineDigest(byte[], int offset, int len).
     *
     * Overrides the protected abstract method of
     * java.security.MessageDigestSpi.
     * @return the length of the digest stored in the output buffer.
     */
    @Override
    public byte[] engineDigest() {
        try {
            final byte hashvalue[] = new byte[HASH_LENGTH];
            engineDigest(hashvalue, 0, HASH_LENGTH);
            return hashvalue;
        } catch (DigestException e) {
            return null;
        }
    }
 
    /**
     * Completes the hash computation by performing final operations
     * such as padding. Once engineDigest has been called, the engine
     * will be automatically reset (see engineReset).
     *
     * Overrides the protected abstract method of
     * java.security.MessageDigestSpi.
     * @param hashvalue  the output buffer in which to store the digest.
     * @param offset  offset to start from in the output buffer
     * @param len  number of bytes within buf allotted for the digest.
     *             Both this default implementation and the SUN provider
     *             do not return partial digests.  The presence of this
     *             parameter is solely for consistency in our API's.
     *             If the value of this parameter is less than the
     *             actual digest length, the method will throw a
     *             DigestException.  This parameter is ignored if its
     *             value is greater than or equal to the actual digest
     *             length.
     * @return  the length of the digest stored in the output buffer.
     */
    @Override
    public int engineDigest(byte[] hashvalue, int offset, final int len)
            throws DigestException {
        if (len >= HASH_LENGTH) {
            if (hashvalue.length - offset >= HASH_LENGTH) {
                /* Flush the trailing bytes, adding padding bytes into last
                 * blocks. */
                int i;
                /* Add padding null bytes but replace the last 8 padding bytes
                 * by the little-endian 64-bit digested message bit-length. */
                pad[i = padding] = (byte)0x80; /* required 1st padding byte */
                /* Check if 8 bytes available in pad to store the total
                 * message size */
                switch (i) { /* INVARIANT: i must be in [0..63] */
                case 52: pad[53] = (byte)0x00; /* no break; falls thru */
                case 53: pad[54] = (byte)0x00; /* no break; falls thru */
                case 54: pad[55] = (byte)0x00; /* no break; falls thru */
                case 55: break;
                case 56: pad[57] = (byte)0x00; /* no break; falls thru */
                case 57: pad[58] = (byte)0x00; /* no break; falls thru */
                case 58: pad[59] = (byte)0x00; /* no break; falls thru */
                case 59: pad[60] = (byte)0x00; /* no break; falls thru */
                case 60: pad[61] = (byte)0x00; /* no break; falls thru */
                case 61: pad[62] = (byte)0x00; /* no break; falls thru */
                case 62: pad[63] = (byte)0x00; /* no break; falls thru */
                case 63:
                    computeBlock(pad, 0);
                    /* Clear the 56 first bytes of pad[]. */
                    i = 52;
                    do {
                        pad[i    ] = (byte)0x00;
                        pad[i + 1] = (byte)0x00;
                        pad[i + 2] = (byte)0x00;
                        pad[i + 3] = (byte)0x00;
                    } while ((i -= 4) >= 0);
                    break;
                default:
                    /* Clear the rest of 56 first bytes of pad[]. */
                    switch (i & 3) {
                    case 3: i++;
                            break;
                    case 2: pad[(i += 2) - 1] = (byte)0x00;
                            break;
                    case 1: pad[(i += 3) - 2] = (byte)0x00;
                            pad[ i       - 1] = (byte)0x00;
                            break;
                    case 0: pad[(i += 4) - 3] = (byte)0x00;
                            pad[ i       - 2] = (byte)0x00;
                            pad[ i       - 1] = (byte)0x00;
                    }
                    do {
                        pad[i    ] = (byte)0x00;
                        pad[i + 1] = (byte)0x00;
                        pad[i + 2] = (byte)0x00;
                        pad[i + 3] = (byte)0x00;
                    } while ((i += 4) < 56);
                }
                /* Convert the message size from bytes to big-endian bits. */
                pad[56] = (byte)((i = (int)(bytes >>> 29)) >> 24);
                pad[57] = (byte)(i >>> 16);
                pad[58] = (byte)(i >>> 8);
                pad[59] = (byte)i;
                pad[60] = (byte)((i = (int)bytes << 3) >> 24);
                pad[61] = (byte)(i >>> 16);
                pad[62] = (byte)(i >>> 8);
                pad[63] = (byte)i;
                computeBlock(pad, 0);
                /* Return the computed digest in big-endian byte order. */
                hashvalue[offset     ] = (byte)((i = hA) >>> 24);
                hashvalue[offset +  1] = (byte)(i >>> 16);
                hashvalue[offset +  2] = (byte)(i >>> 8);
                hashvalue[offset +  3] = (byte)i;
                hashvalue[offset +  4] = (byte)((i = hB) >>> 24);
                hashvalue[offset += 5] = (byte)(i >>> 16);
                hashvalue[offset +  1] = (byte)(i >>> 8);
                hashvalue[offset +  2] = (byte)i;
                hashvalue[offset +  3] = (byte)((i = hC) >>> 24);
                hashvalue[offset +  4] = (byte)(i >>> 16);
                hashvalue[offset += 5] = (byte)(i >>> 8);
                hashvalue[offset +  1] = (byte)i;
                hashvalue[offset +  2] = (byte)((i = hD) >>> 24);
                hashvalue[offset +  3] = (byte)(i >>> 16);
                hashvalue[offset +  4] = (byte)(i >>> 8);
                hashvalue[offset += 5] = (byte)i;
                hashvalue[offset +  1] = (byte)((i = hE) >>> 24);
                hashvalue[offset +  2] = (byte)(i >>> 16);
                hashvalue[offset +  3] = (byte)(i >>> 8);
                hashvalue[offset +  4] = (byte)i;
                engineReset(); /* clear the evidence */
                return HASH_LENGTH;
            }
            throw new DigestException(
                "insufficient space in output buffer to store the digest");
        }
        throw new DigestException("partial digests not returned");
    }
 
    /**
     * Updates the digest using the specified array of bytes,
     * starting at the specified offset, but an implied length
     * of exactly 64 bytes.
     *
     * Requires no internal buffering, but assumes a fixed input size,
     * in which the required padding bytes may have been added.
     *
     * @param input  the array of bytes to use for the update.
     * @param offset  the offset to start from in the array of bytes.
     */
    private void computeBlock(final byte[] input, int offset) {
        /* Local temporary work variables for intermediate digests. */
        int a, b, c, d, e;
        /* Cache the input block into the local working set of 32-bit
         * values, in big-endian byte order. Be careful when
         * widening bytes or integers due to sign extension! */
        int i00, i01, i02, i03, i04, i05, i06, i07,
            i08, i09, i10, i11, i12, i13, i14, i15;
        /* Use hash schedule function Ch (rounds 0..19):
         *   Ch(x,y,z) = (x & y) ^ (~x & z) = (x & (y ^ z)) ^ z,
         * and K00 = .... = K19 = 0x5a827999. */
        /* First pass, on big endian input (rounds 0..15). */
        e =  hE
          +  (((a = hA) << 5) | (a >>> 27)) + 0x5a827999 // K00
          +  (((b = hB) & ((c = hC)      ^ (d = hD))) ^ d) // Ch(b,c,d)
          +  (i00 =  input[offset     ] << 24
                  | (input[offset +  1] & 0xff) << 16
                  | (input[offset +  2] & 0xff) << 8
                  | (input[offset +  3] & 0xff)); // W00
        d += ((e << 5) | (e >>> 27)) + 0x5a827999 // K01
          +  ((a & ((b = (b << 30) | (b >>> 2)) ^ c)) ^ c) // Ch(a,b,c)
          +  (i01 =  input[offset +  4] << 24
                  | (input[offset += 5] & 0xff) << 16
                  | (input[offset +  1] & 0xff) << 8
                  | (input[offset +  2] & 0xff)); // W01
        c += ((d << 5) | (d >>> 27)) + 0x5a827999 // K02
          +  ((e & ((a = (a << 30) | (a >>> 2)) ^ b)) ^ b) // Ch(e,a,b)
          +  (i02 =  input[offset +  3] << 24
                  | (input[offset +  4] & 0xff) << 16
                  | (input[offset += 5] & 0xff) << 8
                  | (input[offset +  1] & 0xff)); // W02
        b += ((c << 5) | (c >>> 27)) + 0x5a827999 // K03
          +  ((d & ((e = (e << 30) | (e >>> 2)) ^ a)) ^ a) // Ch(d,e,a)
          +  (i03 =  input[offset +  2] << 24
                  | (input[offset +  3] & 0xff) << 16
                  | (input[offset +  4] & 0xff) << 8
                  | (input[offset += 5] & 0xff)); // W03
        a += ((b << 5) | (b >>> 27)) + 0x5a827999 // K04
          +  ((c & ((d = (d << 30) | (d >>> 2)) ^ e)) ^ e) // Ch(c,d,e)
          +  (i04 =  input[offset +  1] << 24
                  | (input[offset +  2] & 0xff) << 16
                  | (input[offset +  3] & 0xff) << 8
                  | (input[offset +  4] & 0xff)); // W04
        e += ((a << 5) | (a >>> 27)) + 0x5a827999 // K05
          +  ((b & ((c = (c << 30) | (c >>> 2)) ^ d)) ^ d) // Ch(b,c,d)
          +  (i05 =  input[offset += 5] << 24
                  | (input[offset +  1] & 0xff) << 16
                  | (input[offset +  2] & 0xff) << 8
                  | (input[offset +  3] & 0xff)); // W05
        d += ((e << 5) | (e >>> 27)) + 0x5a827999 // K06
          +  ((a & ((b = (b << 30) | (b >>> 2)) ^ c)) ^ c) // Ch(a,b,c)
          +  (i06 =  input[offset +  4] << 24
                  | (input[offset += 5] & 0xff) << 16
                  | (input[offset +  1] & 0xff) << 8
                  | (input[offset +  2] & 0xff)); // W06
        c += ((d << 5) | (d >>> 27)) + 0x5a827999 // K07
          +  ((e & ((a = (a << 30) | (a >>> 2)) ^ b)) ^ b) // Ch(e,a,b)
          +  (i07 =  input[offset +  3] << 24
                  | (input[offset +  4] & 0xff) << 16
                  | (input[offset += 5] & 0xff) << 8
                  | (input[offset +  1] & 0xff)); // W07
        b += ((c << 5) | (c >>> 27)) + 0x5a827999 // K08
          +  ((d & ((e = (e << 30) | (e >>> 2)) ^ a)) ^ a) // Ch(d,e,a)
          +  (i08 =  input[offset +  2] << 24
                  | (input[offset +  3] & 0xff) << 16
                  | (input[offset +  4] & 0xff) << 8
                  | (input[offset += 5] & 0xff)); // W08
        a += ((b << 5) | (b >>> 27)) + 0x5a827999 // K09
          +  ((c & ((d = (d << 30) | (d >>> 2)) ^ e)) ^ e) // Ch(c,d,e)
          +  (i09 =  input[offset +  1] << 24
                  | (input[offset +  2] & 0xff) << 16
                  | (input[offset +  3] & 0xff) << 8
                  | (input[offset +  4] & 0xff)); // W09
        e += ((a << 5) | (a >>> 27)) + 0x5a827999 // K10
          +  ((b & ((c = (c << 30) | (c >>> 2)) ^ d)) ^ d) // Ch(b,c,d)
          +  (i10 =  input[offset += 5] << 24
                  | (input[offset +  1] & 0xff) << 16
                  | (input[offset +  2] & 0xff) << 8
                  | (input[offset +  3] & 0xff)); // W10
        d += ((e << 5) | (e >>> 27)) + 0x5a827999 // K11
          +  ((a & ((b = (b << 30) | (b >>> 2)) ^ c)) ^ c) // Ch(a,b,c)
          +  (i11 =  input[offset +  4] << 24
                  | (input[offset += 5] & 0xff) << 16
                  | (input[offset +  1] & 0xff) << 8
                  | (input[offset +  2] & 0xff)); // W11
        c += ((d << 5) | (d >>> 27)) + 0x5a827999 // K12
          +  ((e & ((a = (a << 30) | (a >>> 2)) ^ b)) ^ b) // Ch(e,a,b)
          +  (i12 =  input[offset +  3] << 24
                  | (input[offset +  4] & 0xff) << 16
                  | (input[offset += 5] & 0xff) << 8
                  | (input[offset +  1] & 0xff)); // W12
        b += ((c << 5) | (c >>> 27)) + 0x5a827999 // K13
          +  ((d & ((e = (e << 30) | (e >>> 2)) ^ a)) ^ a) // Ch(d,e,a)
          +  (i13 =  input[offset +  2] << 24
                  | (input[offset +  3] & 0xff) << 16
                  | (input[offset +  4] & 0xff) << 8
                  | (input[offset += 5] & 0xff)); // W13
        a += ((b << 5) | (b >>> 27)) + 0x5a827999 // K14
          +  ((c & ((d = (d << 30) | (d >>> 2)) ^ e)) ^ e) // Ch(c,d,e)
          +  (i14 =  input[offset +  1] << 24
                  | (input[offset +  2] & 0xff) << 16
                  | (input[offset +  3] & 0xff) << 8
                  | (input[offset +  4] & 0xff)); // W14
        e += ((a << 5) | (a >>> 27)) + 0x5a827999 // K15
          +  ((b & ((c = (c << 30) | (c >>> 2)) ^ d)) ^ d) // Ch(b,c,d)
          +  (i15 =  input[offset += 5] << 24
                  | (input[offset +  1] & 0xff) << 16
                  | (input[offset +  2] & 0xff) << 8
                  | (input[offset +  3] & 0xff)); // W15
        /* Second pass, on scheduled input (rounds 16..31). */
        d += ((e << 5) | (e >>> 27)) + 0x5a827999 // K16
          +  ((a & ((b = (b << 30) | (b >>> 2)) ^ c)) ^ c) // Ch(a,b,c)
          +  (i00 = ((i00 ^= i02 ^ i08 ^ i13) << 1) | (i00 >>> 31)); // W16
        c += ((d << 5) | (d >>> 27)) + 0x5a827999 // K17
          +  ((e & ((a = (a << 30) | (a >>> 2)) ^ b)) ^ b) // Ch(e,a,b)
          +  (i01 = ((i01 ^= i03 ^ i09 ^ i14) << 1) | (i01 >>> 31)); // W17
        b += ((c << 5) | (c >>> 27)) + 0x5a827999 // K18
          +  ((d & ((e = (e << 30) | (e >>> 2)) ^ a)) ^ a) // Ch(d,e,a)
          +  (i02 = ((i02 ^= i04 ^ i10 ^ i15) << 1) | (i02 >>> 31)); // W18
        a += ((b << 5) | (b >>> 27)) + 0x5a827999 // K19
          +  ((c & ((d = (d << 30) | (d >>> 2)) ^ e)) ^ e) // Ch(c,d,e)
          +  (i03 = ((i03 ^= i05 ^ i11 ^ i00) << 1) | (i03 >>> 31)); // W19
        /* Use hash schedule function Parity (rounds 20..39):
         *   Parity(x,y,z) = x ^ y ^ z,
         * and K20 = .... = K39 = 0x6ed9eba1. */
        e += ((a << 5) | (a >>> 27)) + 0x6ed9eba1 // K20
          +  (b ^ (c = (c << 30) | (c >>> 2)) ^ d) // Parity(b,c,d)
          +  (i04 = ((i04 ^= i06 ^ i12 ^ i01) << 1) | (i04 >>> 31)); // W20
        d += ((e << 5) | (e >>> 27)) + 0x6ed9eba1 // K21
          +  (a ^ (b = (b << 30) | (b >>> 2)) ^ c) // Parity(a,b,c)
          +  (i05 = ((i05 ^= i07 ^ i13 ^ i02) << 1) | (i05 >>> 31)); // W21
        c += ((d << 5) | (d >>> 27)) + 0x6ed9eba1 // K22
          +  (e ^ (a = (a << 30) | (a >>> 2)) ^ b) // Parity(e,a,b)
          +  (i06 = ((i06 ^= i08 ^ i14 ^ i03) << 1) | (i06 >>> 31)); // W22
        b += ((c << 5) | (c >>> 27)) + 0x6ed9eba1 // K23
          +  (d ^ (e = (e << 30) | (e >>> 2)) ^ a) // Parity(d,e,a)
          +  (i07 = ((i07 ^= i09 ^ i15 ^ i04) << 1) | (i07 >>> 31)); // W23
        a += ((b << 5) | (b >>> 27)) + 0x6ed9eba1 // K24
          +  (c ^ (d = (d << 30) | (d >>> 2)) ^ e) // Parity(c,d,e)
          +  (i08 = ((i08 ^= i10 ^ i00 ^ i05) << 1) | (i08 >>> 31)); // W24
        e += ((a << 5) | (a >>> 27)) + 0x6ed9eba1 // K25
          +  (b ^ (c = (c << 30) | (c >>> 2)) ^ d) // Parity(b,c,d)
          +  (i09 = ((i09 ^= i11 ^ i01 ^ i06) << 1) | (i09 >>> 31)); // W25
        d += ((e << 5) | (e >>> 27)) + 0x6ed9eba1 // K26
          +  (a ^ (b = (b << 30) | (b >>> 2)) ^ c) // Parity(a,b,c)
          +  (i10 = ((i10 ^= i12 ^ i02 ^ i07) << 1) | (i10 >>> 31)); // W26
        c += ((d << 5) | (d >>> 27)) + 0x6ed9eba1 // K27
          +  (e ^ (a = (a << 30) | (a >>> 2)) ^ b) // Parity(e,a,b)
          +  (i11 = ((i11 ^= i13 ^ i03 ^ i08) << 1) | (i11 >>> 31)); // W27
        b += ((c << 5) | (c >>> 27)) + 0x6ed9eba1 // K28
          +  (d ^ (e = (e << 30) | (e >>> 2)) ^ a) // Parity(d,e,a)
          +  (i12 = ((i12 ^= i14 ^ i04 ^ i09) << 1) | (i12 >>> 31)); // W28
        a += ((b << 5) | (b >>> 27)) + 0x6ed9eba1 // K29
          +  (c ^ (d = (d << 30) | (d >>> 2)) ^ e) // Parity(c,d,e)
          +  (i13 = ((i13 ^= i15 ^ i05 ^ i10) << 1) | (i13 >>> 31)); // W29
        e += ((a << 5) | (a >>> 27)) + 0x6ed9eba1 // K30
          +  (b ^ (c = (c << 30) | (c >>> 2)) ^ d) // Parity(b,c,d)
          +  (i14 = ((i14 ^= i00 ^ i06 ^ i11) << 1) | (i14 >>> 31)); // W30
        d += ((e << 5) | (e >>> 27)) + 0x6ed9eba1 // K31
          +  (a ^ (b = (b << 30) | (b >>> 2)) ^ c) // Parity(a,b,c)
          +  (i15 = ((i15 ^= i01 ^ i07 ^ i12) << 1) | (i15 >>> 31)); // W31
        /* Third pass, on scheduled input (rounds 32..47). */
        c += ((d << 5) | (d >>> 27)) + 0x6ed9eba1 // K32
          +  (e ^ (a = (a << 30) | (a >>> 2)) ^ b) // Parity(e,a,b)
          +  (i00 = ((i00 ^= i02 ^ i08 ^ i13) << 1) | (i00 >>> 31)); // W32
        b += ((c << 5) | (c >>> 27)) + 0x6ed9eba1 // K33
          +  (d ^ (e = (e << 30) | (e >>> 2)) ^ a) // Parity(d,e,a)
          +  (i01 = ((i01 ^= i03 ^ i09 ^ i14) << 1) | (i01 >>> 31)); // W33
        a += ((b << 5) | (b >>> 27)) + 0x6ed9eba1 // K34
          +  (c ^ (d = (d << 30) | (d >>> 2)) ^ e) // Parity(c,d,e)
          +  (i02 = ((i02 ^= i04 ^ i10 ^ i15) << 1) | (i02 >>> 31)); // W34
        e += ((a << 5) | (a >>> 27)) + 0x6ed9eba1 // K35
          +  (b ^ (c = (c << 30) | (c >>> 2)) ^ d) // Parity(b,c,d)
          +  (i03 = ((i03 ^= i05 ^ i11 ^ i00) << 1) | (i03 >>> 31)); // W35
        d += ((e << 5) | (e >>> 27)) + 0x6ed9eba1 // K36
          +  (a ^ (b = (b << 30) | (b >>> 2)) ^ c) // Parity(a,b,c)
          +  (i04 = ((i04 ^= i06 ^ i12 ^ i01) << 1) | (i04 >>> 31)); // W36
        c += ((d << 5) | (d >>> 27)) + 0x6ed9eba1 // K37
          +  (e ^ (a = (a << 30) | (a >>> 2)) ^ b) // Parity(e,a,b)
          +  (i05 = ((i05 ^= i07 ^ i13 ^ i02) << 1) | (i05 >>> 31)); // W37
        b += ((c << 5) | (c >>> 27)) + 0x6ed9eba1 // K38
          +  (d ^ (e = (e << 30) | (e >>> 2)) ^ a) // Parity(d,e,a)
          +  (i06 = ((i06 ^= i08 ^ i14 ^ i03) << 1) | (i06 >>> 31)); // W38
        a += ((b << 5) | (b >>> 27)) + 0x6ed9eba1 // K39
          +  (c ^ (d = (d << 30) | (d >>> 2)) ^ e) // Parity(c,d,e)
          +  (i07 = ((i07 ^= i09 ^ i15 ^ i04) << 1) | (i07 >>> 31)); // W39
        /* Use hash schedule function Maj (rounds 40..59):
         *   Maj(x,y,z) = (x&y) ^ (x&z) ^ (y&z) = (x & y) | ((x | y) & z),
         * and K40 = .... = K59 = 0x8f1bbcdc. */
        e += ((a << 5) | (a >>> 27)) + 0x8f1bbcdc // K40
          +  ((b & (c = (c << 30) | (c >>> 2))) | ((b | c) & d)) // Maj(b,c,d)
          +  (i08 = ((i08 ^= i10 ^ i00 ^ i05) << 1) | (i08 >>> 31)); // W40
        d += ((e << 5) | (e >>> 27)) + 0x8f1bbcdc // K41
          +  ((a & (b = (b << 30) | (b >>> 2))) | ((a | b) & c)) // Maj(a,b,c)
          +  (i09 = ((i09 ^= i11 ^ i01 ^ i06) << 1) | (i09 >>> 31)); // W41
        c += ((d << 5) | (d >>> 27)) + 0x8f1bbcdc // K42
          +  ((e & (a = (a << 30) | (a >>> 2))) | ((e | a) & b)) // Maj(e,a,b)
          +  (i10 = ((i10 ^= i12 ^ i02 ^ i07) << 1) | (i10 >>> 31)); // W42
        b += ((c << 5) | (c >>> 27)) + 0x8f1bbcdc // K43
          +  ((d & (e = (e << 30) | (e >>> 2))) | ((d | e) & a)) // Maj(d,e,a)
          +  (i11 = ((i11 ^= i13 ^ i03 ^ i08) << 1) | (i11 >>> 31)); // W43
        a += ((b << 5) | (b >>> 27)) + 0x8f1bbcdc // K44
          +  ((c & (d = (d << 30) | (d >>> 2))) | ((c | d) & e)) // Maj(c,d,e)
          +  (i12 = ((i12 ^= i14 ^ i04 ^ i09) << 1) | (i12 >>> 31)); // W44
        e += ((a << 5) | (a >>> 27)) + 0x8f1bbcdc // K45
          +  ((b & (c = (c << 30) | (c >>> 2))) | ((b | c) & d)) // Maj(b,c,d)
          +  (i13 = ((i13 ^= i15 ^ i05 ^ i10) << 1) | (i13 >>> 31)); // W45
        d += ((e << 5) | (e >>> 27)) + 0x8f1bbcdc // K46
          +  ((a & (b = (b << 30) | (b >>> 2))) | ((a | b) & c)) // Maj(a,b,c)
          +  (i14 = ((i14 ^= i00 ^ i06 ^ i11) << 1) | (i14 >>> 31)); // W46
        c += ((d << 5) | (d >>> 27)) + 0x8f1bbcdc // K47
          +  ((e & (a = (a << 30) | (a >>> 2))) | ((e | a) & b)) // Maj(e,a,b)
          +  (i15 = ((i15 ^= i01 ^ i07 ^ i12) << 1) | (i15 >>> 31)); // W47
        /* Fourth pass, on scheduled input (rounds 48..63). */
        b += ((c << 5) | (c >>> 27)) + 0x8f1bbcdc // K48
          +  ((d & (e = (e << 30) | (e >>> 2))) | ((d | e) & a)) // Maj(d,e,a)
          +  (i00 = ((i00 ^= i02 ^ i08 ^ i13) << 1) | (i00 >>> 31)); // W48
        a += ((b << 5) | (b >>> 27)) + 0x8f1bbcdc // K49
          +  ((c & (d = (d << 30) | (d >>> 2))) | ((c | d) & e)) // Maj(c,d,e)
          +  (i01 = ((i01 ^= i03 ^ i09 ^ i14) << 1) | (i01 >>> 31)); // W49
        e += ((a << 5) | (a >>> 27)) + 0x8f1bbcdc // K50
          +  ((b & (c = (c << 30) | (c >>> 2))) | ((b | c) & d)) // Maj(b,c,d)
          +  (i02 = ((i02 ^= i04 ^ i10 ^ i15) << 1) | (i02 >>> 31)); // W50
        d += ((e << 5) | (e >>> 27)) + 0x8f1bbcdc // K51
          +  ((a & (b = (b << 30) | (b >>> 2))) | ((a | b) & c)) // Maj(a,b,c)
          +  (i03 = ((i03 ^= i05 ^ i11 ^ i00) << 1) | (i03 >>> 31)); // W51
        c += ((d << 5) | (d >>> 27)) + 0x8f1bbcdc // K52
          +  ((e & (a = (a << 30) | (a >>> 2))) | ((e | a) & b)) // Maj(e,a,b)
          +  (i04 = ((i04 ^= i06 ^ i12 ^ i01) << 1) | (i04 >>> 31)); // W52
        b += ((c << 5) | (c >>> 27)) + 0x8f1bbcdc // K53
          +  ((d & (e = (e << 30) | (e >>> 2))) | ((d | e) & a)) // Maj(d,e,a)
          +  (i05 = ((i05 ^= i07 ^ i13 ^ i02) << 1) | (i05 >>> 31)); // W53
        a += ((b << 5) | (b >>> 27)) + 0x8f1bbcdc // K54
          +  ((c & (d = (d << 30) | (d >>> 2))) | ((c | d) & e)) // Maj(c,d,e)
          +  (i06 = ((i06 ^= i08 ^ i14 ^ i03) << 1) | (i06 >>> 31)); // W54
        e += ((a << 5) | (a >>> 27)) + 0x8f1bbcdc // K55
          +  ((b & (c = (c << 30) | (c >>> 2))) | ((b | c) & d)) // Maj(b,c,d)
          +  (i07 = ((i07 ^= i09 ^ i15 ^ i04) << 1) | (i07 >>> 31)); // W55
        d += ((e << 5) | (e >>> 27)) + 0x8f1bbcdc // K56
          +  ((a & (b = (b << 30) | (b >>> 2))) | ((a | b) & c)) // Maj(a,b,c)
          +  (i08 = ((i08 ^= i10 ^ i00 ^ i05) << 1) | (i08 >>> 31)); // W56
        c += ((d << 5) | (d >>> 27)) + 0x8f1bbcdc // K57
          +  ((e & (a = (a << 30) | (a >>> 2))) | ((e | a) & b)) // Maj(e,a,b)
          +  (i09 = ((i09 ^= i11 ^ i01 ^ i06) << 1) | (i09 >>> 31)); // W57
        b += ((c << 5) | (c >>> 27)) + 0x8f1bbcdc // K58
          +  ((d & (e = (e << 30) | (e >>> 2))) | ((d | e) & a)) // Maj(d,e,a)
          +  (i10 = ((i10 ^= i12 ^ i02 ^ i07) << 1) | (i10 >>> 31)); // W58
        a += ((b << 5) | (b >>> 27)) + 0x8f1bbcdc // K59
          +  ((c & (d = (d << 30) | (d >>> 2))) | ((c | d) & e)) // Maj(c,d,e)
          +  (i11 = ((i11 ^= i13 ^ i03 ^ i08) << 1) | (i11 >>> 31)); // W59
        /* Use hash schedule function Parity (rounds 60..79):
         *   Parity(x,y,z) = x ^ y ^ z,
         * and K60 = .... = K79 = 0xca62c1d6. */
        e += ((a << 5) | (a >>> 27)) + 0xca62c1d6 // K60
          +  (b ^ (c = (c << 30) | (c >>> 2)) ^ d) // Parity(b,c,d)
          +  (i12 = ((i12 ^= i14 ^ i04 ^ i09) << 1) | (i12 >>> 31)); // W60
        d += ((e << 5) | (e >>> 27)) + 0xca62c1d6 // K61
          +  (a ^ (b = (b << 30) | (b >>> 2)) ^ c) // Parity(a,b,c)
          +  (i13 = ((i13 ^= i15 ^ i05 ^ i10) << 1) | (i13 >>> 31)); // W61
        c += ((d << 5) | (d >>> 27)) + 0xca62c1d6 // K62
          +  (e ^ (a = (a << 30) | (a >>> 2)) ^ b) // Parity(e,a,b)
          +  (i14 = ((i14 ^= i00 ^ i06 ^ i11) << 1) | (i14 >>> 31)); // W62
        b += ((c << 5) | (c >>> 27)) + 0xca62c1d6 // K63
          +  (d ^ (e = (e << 30) | (e >>> 2)) ^ a) // Parity(d,e,a)
          +  (i15 = ((i15 ^= i01 ^ i07 ^ i12) << 1) | (i15 >>> 31)); // W63
        /* Fifth pass, on scheduled input (rounds 64..79). */
        a += ((b << 5) | (b >>> 27)) + 0xca62c1d6 // K64
          +  (c ^ (d = (d << 30) | (d >>> 2)) ^ e) // Parity(c,d,e)
          +  (i00 = ((i00 ^= i02 ^ i08 ^ i13) << 1) | (i00 >>> 31)); // W64
        e += ((a << 5) | (a >>> 27)) + 0xca62c1d6 // K65
          +  (b ^ (c = (c << 30) | (c >>> 2)) ^ d) // Parity(b,c,d)
          +  (i01 = ((i01 ^= i03 ^ i09 ^ i14) << 1) | (i01 >>> 31)); // W65
        d += ((e << 5) | (e >>> 27)) + 0xca62c1d6 // K66
          +  (a ^ (b = (b << 30) | (b >>> 2)) ^ c) // Parity(a,b,c)
          +  (i02 = ((i02 ^= i04 ^ i10 ^ i15) << 1) | (i02 >>> 31)); // W66
        c += ((d << 5) | (d >>> 27)) + 0xca62c1d6 // K67
          +  (e ^ (a = (a << 30) | (a >>> 2)) ^ b) // Parity(e,a,b)
          +  (i03 = ((i03 ^= i05 ^ i11 ^ i00) << 1) | (i03 >>> 31)); // W67
        b += ((c << 5) | (c >>> 27)) + 0xca62c1d6 // K68
          +  (d ^ (e = (e << 30) | (e >>> 2)) ^ a) // Parity(d,e,a)
          +  (i04 = ((i04 ^= i06 ^ i12 ^ i01) << 1) | (i04 >>> 31)); // W68
        a += ((b << 5) | (b >>> 27)) + 0xca62c1d6 // K69
          +  (c ^ (d = (d << 30) | (d >>> 2)) ^ e) // Parity(c,d,e)
          +  (i05 = ((i05 ^= i07 ^ i13 ^ i02) << 1) | (i05 >>> 31)); // W69
        e += ((a << 5) | (a >>> 27)) + 0xca62c1d6 // K70
          +  (b ^ (c = (c << 30) | (c >>> 2)) ^ d) // Parity(b,c,d)
          +  (i06 = ((i06 ^= i08 ^ i14 ^ i03) << 1) | (i06 >>> 31)); // W70
        d += ((e << 5) | (e >>> 27)) + 0xca62c1d6 // K71
          +  (a ^ (b = (b << 30) | (b >>> 2)) ^ c) // Parity(a,b,c)
          +  (i07 = ((i07 ^= i09 ^ i15 ^ i04) << 1) | (i07 >>> 31)); // W71
        c += ((d << 5) | (d >>> 27)) + 0xca62c1d6 // K72
          +  (e ^ (a = (a << 30) | (a >>> 2)) ^ b) // Parity(e,a,b)
          +  (i08 = ((i08 ^= i10 ^ i00 ^ i05) << 1) | (i08 >>> 31)); // W72
        b += ((c << 5) | (c >>> 27)) + 0xca62c1d6 // K73
          +  (d ^ (e = (e << 30) | (e >>> 2)) ^ a) // Parity(d,e,a)
          +  (i09 = ((i09 ^= i11 ^ i01 ^ i06) << 1) | (i09 >>> 31)); // W73
        a += ((b << 5) | (b >>> 27)) + 0xca62c1d6 // K74
          +  (c ^ (d = (d << 30) | (d >>> 2)) ^ e) // Parity(c,d,e)
          +  (i10 = ((i10 ^= i12 ^ i02 ^ i07) << 1) | (i10 >>> 31)); // W74
        e += ((a << 5) | (a >>> 27)) + 0xca62c1d6 // K75
          +  (b ^ (c = (c << 30) | (c >>> 2)) ^ d) // Parity(b,c,d)
          +  (i11 = ((i11 ^= i13 ^ i03 ^ i08) << 1) | (i11 >>> 31)); // W75
        d += ((e << 5) | (e >>> 27)) + 0xca62c1d6 // K76
          +  (a ^ (b = (b << 30) | (b >>> 2)) ^ c) // Parity(a,b,c)
          +  (i12 = ((i12 ^= i14 ^ i04 ^ i09) << 1) | (i12 >>> 31)); // W76
        c += ((d << 5) | (d >>> 27)) + 0xca62c1d6 // K77
          +  (e ^ (a = (a << 30) | (a >>> 2)) ^ b) // Parity(e,a,b)
          +  (i13 = ((i13 ^= i15 ^ i05 ^ i10) << 1) | (i13 >>> 31)); // W77
        /* Terminate the last two rounds of fifth pass,
         * feeding the final digest on the fly. */
        hB +=
        b += ((c << 5) | (c >>> 27)) + 0xca62c1d6 // K78
          +  (d ^ (e = (e << 30) | (e >>> 2)) ^ a) // Parity(d,e,a)
          +  (i14 = ((i14 ^= i00 ^ i06 ^ i11) << 1) | (i14 >>> 31)); // W78
        hA +=
        a += ((b << 5) | (b >>> 27)) + 0xca62c1d6 // K79
          +  (c ^ (d = (d << 30) | (d >>> 2)) ^ e) // Parity(c,d,e)
          +  (i15 = ((i15 ^= i01 ^ i07 ^ i12) << 1) | (i15 >>> 31)); // W79
        hE += e;
        hD += d;
        hC += /* c= */ (c << 30) | (c >>> 2);
    }

    //private static final int RUNS = 100000;

    /**
     *  Test the GNU and the JVM's implementations for speed
     *
     *  Results: 2011-05 eeepc Atom
     *  <pre>
     *  JVM	strlen	GNU ms	JVM  ms 
     *	Oracle	387	  1406	 2357
     *	Oracle	 40	   522	  475
     *	Harmony	387	  5504	 3474
     *	Harmony	 40	  4396	 1593
     *	JamVM	387	 25578	21966
     *	JamVM	 40	  5380	 4195
     *	gij	387	 47225	 3501
     *	gij	 40	  9861    919
     *  </pre>
     *
     *  @since 0.8.7
     */
/****
    public static void main(String args[]) {
        if (args.length <= 0) {
            System.err.println("Usage: SHA1 string");
            return;
        }

        byte[] data = args[0].getBytes();
        SHA1 gnu = new SHA1();
        long start = System.currentTimeMillis();
        for (int i = 0; i < RUNS; i++) {
            gnu.update(data, 0, data.length);
            byte[] sha = gnu.digest();
            if (i == 0)
                System.out.println("SHA1 [" + args[0] + "] = [" + Base64.encode(sha) + "]");
            gnu.reset();
        }
        long time = System.currentTimeMillis() - start;
        System.out.println("Time for " + RUNS + " SHA-256 computations:");
        System.out.println("GNU time (ms): " + time);

        start = System.currentTimeMillis();
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Fatal: " + e);
            return;
        }
        for (int i = 0; i < RUNS; i++) {
            md.reset();
            byte[] sha = md.digest(data);
            if (i == 0)
                System.out.println("SHA1 [" + args[0] + "] = [" + Base64.encode(sha) + "]");
        }
        time = System.currentTimeMillis() - start;

        System.out.println("JVM time (ms): " + time);
    }
****/
}
