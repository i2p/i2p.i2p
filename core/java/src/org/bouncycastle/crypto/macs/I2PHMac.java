package org.bouncycastle.crypto.macs;
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
import java.util.ArrayList;
import java.util.Arrays;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.Mac;

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
 */
public class I2PHMac
implements Mac
{
    private final static int BLOCK_LENGTH = 64;

    private final static byte IPAD = (byte)0x36;
    private final static byte OPAD = (byte)0x5C;

    private Digest digest;
    private int digestSize;
    private byte[] inputPad = new byte[BLOCK_LENGTH];
    private byte[] outputPad = new byte[BLOCK_LENGTH];

    public I2PHMac(
        Digest digest)
    {
        this(digest, digest.getDigestSize()); 
    }
    public I2PHMac(
        Digest digest, int sz)
    {
        this.digest = digest;
        this.digestSize = sz; 
    }

    public String getAlgorithmName()
    {
        return digest.getAlgorithmName() + "/HMAC";
    }

    public Digest getUnderlyingDigest()
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
            digest.doFinal(inputPad, 0);
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

    public int getMacSize()
    {
        return digestSize;
    }

    public void update(
        byte in)
    {
        digest.update(in);
    }

    public void update(
        byte[] in,
        int inOff,
        int len)
    {
        digest.update(in, inOff, len);
    }

    public int doFinal(
        byte[] out,
        int outOff)
    {
        byte[] tmp = acquireTmp(digestSize);
        //byte[] tmp = new byte[digestSize];
        digest.doFinal(tmp, 0);

        digest.update(outputPad, 0, outputPad.length);
        digest.update(tmp, 0, tmp.length);
        releaseTmp(tmp);

        int     len = digest.doFinal(out, outOff);

        reset();

        return len;
    }
    
    /**
     * list of buffers - index 0 is the cache for 32 byte arrays, while index 1 is the cache for 16 byte arrays
     */
    private static ArrayList _tmpBuf[] = new ArrayList[] { new ArrayList(), new ArrayList() };
    private static byte[] acquireTmp(int sz) {
        byte rv[] = null;
        synchronized (_tmpBuf[sz == 32 ? 0 : 1]) {
            if (_tmpBuf[sz == 32 ? 0 : 1].size() > 0)
                rv = (byte[])_tmpBuf[sz == 32 ? 0 : 1].remove(0);
        }
        if (rv != null)
            Arrays.fill(rv, (byte)0x0);
        else
            rv = new byte[sz];
        return rv;
    }
    private static void releaseTmp(byte buf[]) {
        if (buf == null) return;
        synchronized (_tmpBuf[buf.length == 32 ? 0 : 1]) {
            if (_tmpBuf[buf.length == 32 ? 0 : 1].size() < 100) 
                _tmpBuf[buf.length == 32 ? 0 : 1].add((Object)buf);
        }
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
