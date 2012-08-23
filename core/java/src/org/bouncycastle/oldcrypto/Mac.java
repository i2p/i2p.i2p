package org.bouncycastle.oldcrypto;
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


/**
 * The base interface for implementations of message authentication codes (MACs).
 *
 * modified by jrandom to use the session key byte array directly
 */
public interface Mac
{
    /**
     * Initialise the MAC.
     *
     * @param key the key required by the MAC.
     * @exception IllegalArgumentException if the params argument is
     * inappropriate.
     */
    public void init(byte key[])
        throws IllegalArgumentException;

    /**
     * Return the name of the algorithm the MAC implements.
     *
     * @return the name of the algorithm the MAC implements.
     */
    public String getAlgorithmName();

    /**
     * Return the block size for this cipher (in bytes).
     *
     * @return the block size for this cipher in bytes.
     */
    public int getMacSize();

    /**
     * add a single byte to the mac for processing.
     *
     * @param in the byte to be processed.
     * @exception IllegalStateException if the MAC is not initialised.
     */
    public void update(byte in)
        throws IllegalStateException;

    /**
     * @param in the array containing the input.
     * @param inOff the index in the array the data begins at.
     * @param len the length of the input starting at inOff.
     * @exception IllegalStateException if the MAC is not initialised.
     */
    public void update(byte[] in, int inOff, int len)
        throws IllegalStateException;

    /**
     * Compute the final statge of the MAC writing the output to the out
     * parameter.
     * <p>
     * doFinal leaves the MAC in the same state it was after the last init.
     *
     * @param out the array the MAC is to be output to.
     * @param outOff the offset into the out buffer the output is to start at.
     * @exception IllegalStateException if the MAC is not initialised.
     */
    public int doFinal(byte[] out, int outOff)
        throws IllegalStateException;

    /**
     * Reset the MAC. At the end of resetting the MAC should be in the
     * in the same state it was after the last init (if there was one).
     */
    public void reset();
}
