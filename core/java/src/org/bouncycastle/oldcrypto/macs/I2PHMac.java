package org.bouncycastle.oldcrypto.macs;
/*
 * Copyright (c) 2000 - 2004 The Legion Of The Bouncy Castle
 * (http://www.bouncycastle.org)
 *
 * Permission is hereby granted, free of charge, to any person 
 * obtaining a copy of this software and associated 
 * documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to 
 * use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following 
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND 
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT 
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, 
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING 
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR 
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 */

//import org.bouncycastle.crypto.CipherParameters;
import java.security.DigestException;
import java.security.MessageDigest;
import java.util.Arrays;

import net.i2p.util.SimpleByteCache;

import org.bouncycastle.oldcrypto.Mac;

/**
 * HMAC implementation based on RFC2104
 *
 * H(K XOR opad, H(K XOR ipad, text))
 *
 * modified by jrandom to use the session key byte array directly and to cache
 * a frequently used buffer (called on doFinal).  changes released into the public
 * domain in 2005.
 *
 * This is renamed from HMac because the constructor HMac(digest, sz) does not exist
 * in the standard bouncycastle library, thus it conflicts in JVMs that contain the
 * standard library (Android).
 *
 * As of 0.9.12, refactored to use standard MessageDigest.
 *
 * Deprecated - Do not use outside of router or Syndie.
 * To be moved to router.
 */
public class I2PHMac
implements Mac
{
    private final static int BLOCK_LENGTH = 64;

    private final static byte IPAD = (byte)0x36;
    private final static byte OPAD = (byte)0x5C;

    private final MessageDigest digest;
    private final int digestSize;
    private final byte[] inputPad = new byte[BLOCK_LENGTH];
    private final byte[] outputPad = new byte[BLOCK_LENGTH];

    /**
     *  Standard HMAC, size == digest size.
     *  @deprecated Use javax.crypto.Mac
     */
    @Deprecated
    public I2PHMac(MessageDigest digest) {
        this(digest, digest.getDigestLength()); 
    }

    /**
     *  @param sz override the digest's size, nonstandard if different.
     *  SEE NOTES in HMACGenerator about why this isn't compatible with standard HmacMD5
     */
    public I2PHMac(MessageDigest digest, int sz) {
        this.digest = digest;
        this.digestSize = sz; 
    }

    public String getAlgorithmName()
    {
        return digest.getAlgorithm() + "/HMAC";
    }

    public MessageDigest getUnderlyingDigest()
    {
        return digest;
    }

    //public void init(
    //    CipherParameters params)
    //{
    public void init(byte key[]) 
    {
        digest.reset();

        //byte[] key = ((KeyParameter)params).getKey();

        if (key.length > BLOCK_LENGTH)
        {
            digest.update(key, 0, key.length);
            try {
                digest.digest(inputPad, 0, digestSize);
            } catch (DigestException de) {
                digest.reset();
                throw new IllegalArgumentException(de);
            }
            for (int i = digestSize; i < inputPad.length; i++)
            {
                inputPad[i] = 0;
            }
        }
        else
        {
            System.arraycopy(key, 0, inputPad, 0, key.length);
            for (int i = key.length; i < inputPad.length; i++)
            {
                inputPad[i] = 0;
            }
        }

        // why reallocate?  it hasn't changed sizes, and the arraycopy
        // below fills it completely...
        //outputPad = new byte[inputPad.length];
        System.arraycopy(inputPad, 0, outputPad, 0, inputPad.length);

        for (int i = 0; i < inputPad.length; i++)
        {
            inputPad[i] ^= IPAD;
        }

        for (int i = 0; i < outputPad.length; i++)
        {
            outputPad[i] ^= OPAD;
        }

        digest.update(inputPad, 0, inputPad.length);
    }

    public int getMacSize() {
        return digestSize;
    }

    public void update(byte in) {
        digest.update(in);
    }

    public void update(byte[] in, int inOff, int len) {
        digest.update(in, inOff, len);
    }

    public int doFinal(byte[] out, int outOff) {
        byte[] tmp = acquireTmp(digestSize);
        //byte[] tmp = new byte[digestSize];
        try {
            digest.digest(tmp, 0, digestSize);
            digest.update(outputPad, 0, outputPad.length);
            digest.update(tmp, 0, tmp.length);
            return digest.digest(out, outOff, digestSize);
        } catch (DigestException de) {
            throw new IllegalArgumentException(de);
        } finally {
            releaseTmp(tmp);
            reset();
        }
    }
    
    private static byte[] acquireTmp(int sz) {
        byte[] rv = SimpleByteCache.acquire(sz);
        Arrays.fill(rv, (byte)0x0);
        return rv;
    }

    private static void releaseTmp(byte buf[]) {
        SimpleByteCache.release(buf);
    }

    /**
     * Reset the mac generator.
     */
    public void reset()
    {
        /*
         * reset the underlying digest.
         */
        digest.reset();

        /*
         * reinitialize the digest.
         */
        digest.update(inputPad, 0, inputPad.length);
    }
}
