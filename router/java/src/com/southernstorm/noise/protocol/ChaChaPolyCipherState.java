/*
 * Copyright (C) 2016 Southern Storm Software, Pty Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.southernstorm.noise.protocol;

import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;

import com.southernstorm.noise.crypto.chacha20.ChaChaCore;
import com.southernstorm.noise.crypto.Poly1305;

/**
 * Implements the ChaChaPoly cipher for Noise.
 */
public class ChaChaPolyCipherState implements CipherState {

	private final Poly1305 poly;
	private final int[] input;
	private final int[] output;
	private final byte[] polyKey;
	private long n;
	private boolean haskey;
	// Debug only
	private byte[] initialKey;

	private static final boolean DEBUG = false;
	
	/**
	 * Constructs a new cipher state for the "ChaChaPoly" algorithm.
	 */
	public ChaChaPolyCipherState()
	{
		poly = new Poly1305();
		input = new int [16];
		output = new int [16];
		polyKey = new byte [32];
		n = 0;
		haskey = false;
	}

	/**
	 * Copy constructor for cloning
	 * @since 0.9.44
	 */
	protected ChaChaPolyCipherState(ChaChaPolyCipherState o) throws CloneNotSupportedException {
		poly = o.poly.clone();
		input = Arrays.copyOf(o.input, o.input.length);
		output = Arrays.copyOf(o.output, o.output.length);
		polyKey = Arrays.copyOf(o.polyKey, o.polyKey.length);
		n = o.n;
		haskey = o.haskey;
		initialKey = o.initialKey;
	}

	@Override
	public void destroy() {
		poly.destroy();
		Arrays.fill(input, 0);
		Arrays.fill(output, 0);
		Noise.destroy(polyKey);
	}

	@Override
	public String getCipherName() {
		return "ChaChaPoly";
	}

	@Override
	public int getKeyLength() {
		return 32;
	}

	@Override
	public int getMACLength() {
		return haskey ? 16 : 0;
	}

	@Override
	public void initializeKey(byte[] key, int offset) {
		if (DEBUG) {
			initialKey = new byte[32];
			System.arraycopy(key, 0, initialKey, 0, 32);
		}
		ChaChaCore.initKey256(input, key, offset);
		n = 0;
		haskey = true;
	}

	@Override
	public boolean hasKey() {
		return haskey;
	}
	
	/**
	 * Set up to encrypt or decrypt the next packet.
	 * I2P add off/len
	 * 
	 * @param ad The associated data for the packet.
	 * @param off offset
	 * @param len length
	 * @since 0.9.54 added off/len
	 */
	private void setup(byte[] ad, int off, int len)
	{
		if (n == -1L)
			throw new IllegalStateException("Nonce has wrapped around");
		// n will be incremented on success below
		ChaChaCore.initIV(input, n);
		// UNCOMMENT TO RUN THE main() TEST
		// input[13] = TEST_VECTOR_NONCE_HIGH_BYTES;
		ChaChaCore.hash(output, input);
		Arrays.fill(polyKey, (byte)0);
		ChaChaCore.xorBlock(polyKey, 0, polyKey, 0, 32, output);
		poly.reset(polyKey, 0);
		if (ad != null) {
			poly.update(ad, off, len);
			poly.pad();
		}
		if (++(input[12]) == 0)
			++(input[13]);
	}

	/**
	 * Puts a 64-bit integer into a buffer in little-endian order.
	 * 
	 * @param output The output buffer.
	 * @param offset The offset into the output buffer.
	 * @param value The 64-bit integer value.
	 */
	private static void putLittleEndian64(byte[] output, int offset, long value)
	{
		output[offset++] = (byte)value;
		output[offset++] = (byte)(value >> 8);
		output[offset++] = (byte)(value >> 16);
		output[offset++] = (byte)(value >> 24);
		output[offset++] = (byte)(value >> 32);
		output[offset++] = (byte)(value >> 40);
		output[offset++] = (byte)(value >> 48);
		output[offset] = (byte)(value >> 56);
	}

	/**
	 * Finishes up the authentication tag for a packet.
	 * I2P changed ad to adLength; ad data not used here
	 *
	 * @param adLength The length of the associated data, 0 if none.
	 * @param length The length of the plaintext data.
	 * @since 0.9.54 changed ad to adLength
	 */
	private void finish(int adLength, int length)
	{
		poly.pad();
		putLittleEndian64(polyKey, 0, adLength);
		putLittleEndian64(polyKey, 8, length);
		poly.update(polyKey, 0, 16);
		poly.finish(polyKey, 0);
	}

	/**
	 * Encrypts or decrypts a buffer of bytes for the active packet.
	 * 
	 * @param plaintext The plaintext data to be encrypted.
	 * @param plaintextOffset The offset to the first plaintext byte.
	 * @param ciphertext The ciphertext data that results from encryption.
	 * @param ciphertextOffset The offset to the first ciphertext byte.
	 * @param length The number of bytes to encrypt.
	 */
	private void encrypt(byte[] plaintext, int plaintextOffset,
			byte[] ciphertext, int ciphertextOffset, int length) {
		while (length > 0) {
			int tempLen = 64;
			if (tempLen > length)
				tempLen = length;
			ChaChaCore.hash(output, input);
			ChaChaCore.xorBlock(plaintext, plaintextOffset, ciphertext, ciphertextOffset, tempLen, output);
			if (++(input[12]) == 0)
				++(input[13]);
			plaintextOffset += tempLen;
			ciphertextOffset += tempLen;
			length -= tempLen;
		}
	}

	@Override
	public int encryptWithAd(byte[] ad, byte[] plaintext, int plaintextOffset,
	                         byte[] ciphertext, int ciphertextOffset, int length) throws ShortBufferException {
		return encryptWithAd(ad, 0, ad != null ? ad.length : 0, plaintext, plaintextOffset,
		                     ciphertext, ciphertextOffset, length);
	}

	/**
	 *  I2P
	 *  @since 0.9.54
	 */
	@Override
	public int encryptWithAd(byte[] ad, int adOffset, int adLength, byte[] plaintext, int plaintextOffset,
	                         byte[] ciphertext, int ciphertextOffset, int length) throws ShortBufferException {
		int space;
		if (ciphertextOffset > ciphertext.length)
			space = 0;
		else
			space = ciphertext.length - ciphertextOffset;
		if (!haskey) {
			// The key is not set yet - return the plaintext as-is.
			if (length > space)
				throw new ShortBufferException();
			if (plaintext != ciphertext || plaintextOffset != ciphertextOffset)
				System.arraycopy(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
			return length;
		}
		if (space < 16 || length > (space - 16))
			throw new ShortBufferException();
		setup(ad, adOffset, adLength);
		encrypt(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
		poly.update(ciphertext, ciphertextOffset, length);
		finish(adLength, length);
		System.arraycopy(polyKey, 0, ciphertext, ciphertextOffset + length, 16);
		n++;
		return length + 16;
	}

	@Override
	public int decryptWithAd(byte[] ad, byte[] ciphertext,
	                         int ciphertextOffset, byte[] plaintext, int plaintextOffset,
	                         int length) throws ShortBufferException, BadPaddingException {
		return decryptWithAd(ad, 0, ad != null ? ad.length : 0, ciphertext, ciphertextOffset,
		                     plaintext, plaintextOffset, length);
	}

	/**
	 *  I2P
	 *  @since 0.9.54
	 */
	@Override
	public int decryptWithAd(byte[] ad, int adOffset, int adLength, byte[] ciphertext,
	                         int ciphertextOffset, byte[] plaintext, int plaintextOffset,
	                         int length) throws ShortBufferException, BadPaddingException {
		int space;
		if (ciphertextOffset > ciphertext.length)
			space = 0;
		else
			space = ciphertext.length - ciphertextOffset;
		if (length > space)
			throw new ShortBufferException();
		if (plaintextOffset > plaintext.length)
			space = 0;
		else
			space = plaintext.length - plaintextOffset;
		if (!haskey) {
			// The key is not set yet - return the ciphertext as-is.
			if (length > space)
				throw new ShortBufferException();
			if (plaintext != ciphertext || plaintextOffset != ciphertextOffset)
				System.arraycopy(ciphertext, ciphertextOffset, plaintext, plaintextOffset, length);
			return length;
		}
		if (length < 16)
			Noise.throwBadTagException();
		int dataLen = length - 16;
		if (dataLen > space)
			throw new ShortBufferException();
		setup(ad, adOffset, adLength);
		poly.update(ciphertext, ciphertextOffset, dataLen);
		finish(adLength, dataLen);
		int temp = 0;
		for (int index = 0; index < 16; ++index)
			temp |= (polyKey[index] ^ ciphertext[ciphertextOffset + dataLen + index]);
		if ((temp & 0xFF) != 0)
			Noise.throwBadTagException();
		encrypt(ciphertext, ciphertextOffset, plaintext, plaintextOffset, dataLen);
		n++;
		return dataLen;
	}

	@Override
	public CipherState fork(byte[] key, int offset) {
		CipherState cipher = new ChaChaPolyCipherState();
		cipher.initializeKey(key, offset);
		return cipher;
	}

	@Override
	public void setNonce(long nonce) {
		n = nonce;
	}

	/**
	 *  I2P
	 *  @since 0.9.44
	 */
	@Override
	public ChaChaPolyCipherState clone() throws CloneNotSupportedException {
		return new ChaChaPolyCipherState(this);
	}

	/**
	 *  I2P debug
	 */
	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder();
		buf.append("  Cipher State:\n" +
		           "    nonce: ");
		buf.append(n);
		// I2P debug
		if (DEBUG) {
			buf.append("\n" +
			           "    init key: ");
			if (haskey)
				buf.append(net.i2p.data.Base64.encode(initialKey));
			else
				buf.append("null");
		}
		buf.append("\n    poly key: ");
		if (haskey)
			buf.append(net.i2p.data.Base64.encode(polyKey));
		else
			buf.append("null");
		buf.append('\n');
		return buf.toString();
	}

    //private static final int TEST_VECTOR_NONCE_HIGH_BYTES = 0x00000007;
    //private static final long TEST_VECTOR_NONCE_LOW_BYTES = 0x4746454443424140L;

    /**
     *  IMPORTANT NOTE:
     *  To run this test you must uncomment the line in
     *  setup() above to set the high 4 bytes of the block counter,
     *  because the test vector has a 12 byte nonce with the high 4 bytes nonzero
     */
/****
    public static void main(String[] args) throws Exception {
        // vectors as in RFC 7539
        // adapted from https://github.com/PurpleI2P/i2pd/blob/openssl/tests/test-aeadchacha20poly1305.cpp
        byte[] plaintext = net.i2p.data.DataHelper.getASCII("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.");
        System.out.println("Plaintext");
        System.out.println(net.i2p.util.HexDump.dump(plaintext));
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) (i + 0x80);
        }
        // nonce
        // we have to put the high 4 bytes of the IV into the counter because our code is 8/8 not 4/12
        // 0x07, 0x00, 0x00, 0x00, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47
        byte[] ad = { 0x50, 0x51, 0x52, 0x53, (byte) 0xc0, (byte) 0xc1, (byte) 0xc2, (byte) 0xc3, (byte) 0xc4, (byte) 0xc5, (byte) 0xc6, (byte) 0xc7 };
        byte[] out = new byte[plaintext.length + 16];
        ChaChaPolyCipherState cha = new ChaChaPolyCipherState();
        cha.initializeKey(key, 0);
        cha.setNonce(TEST_VECTOR_NONCE_LOW_BYTES);
        cha.encryptWithAd(ad, plaintext, 0, out, 0, plaintext.length);
        System.out.println("Ciphertext");
        System.out.println(net.i2p.util.HexDump.dump(out));
        System.out.println("Tag");
        System.out.println(net.i2p.util.HexDump.dump(out, out.length - 16, 16));
        // encrypted
        // 0xd3, 0x1a, 0x8d, 0x34, 0x64, 0x8e, 0x60, 0xdb, 0x7b, 0x86, 0xaf, 0xbc, 0x53, 0xef, 0x7e, 0xc2,
        // 0xa4, 0xad, 0xed, 0x51, 0x29, 0x6e, 0x08, 0xfe, 0xa9, 0xe2, 0xb5, 0xa7, 0x36, 0xee, 0x62, 0xd6,
        // 0x3d, 0xbe, 0xa4, 0x5e, 0x8c, 0xa9, 0x67, 0x12, 0x82, 0xfa, 0xfb, 0x69, 0xda, 0x92, 0x72, 0x8b,
        // 0x1a, 0x71, 0xde, 0x0a, 0x9e, 0x06, 0x0b, 0x29, 0x05, 0xd6, 0xa5, 0xb6, 0x7e, 0xcd, 0x3b, 0x36,
        // 0x92, 0xdd, 0xbd, 0x7f, 0x2d, 0x77, 0x8b, 0x8c, 0x98, 0x03, 0xae, 0xe3, 0x28, 0x09, 0x1b, 0x58,
        // 0xfa, 0xb3, 0x24, 0xe4, 0xfa, 0xd6, 0x75, 0x94, 0x55, 0x85, 0x80, 0x8b, 0x48, 0x31, 0xd7, 0xbc,
        // 0x3f, 0xf4, 0xde, 0xf0, 0x8e, 0x4b, 0x7a, 0x9d, 0xe5, 0x76, 0xd2, 0x65, 0x86, 0xce, 0xc6, 0x4b,
        // 0x61, 0x16
        // tag
        // 0x1a, 0xe1, 0x0b, 0x59, 0x4f, 0x09, 0xe2, 0x6a, 0x7e, 0x90, 0x2e, 0xcb, 0xd0, 0x60, 0x06, 0x91
        cha.initializeKey(key, 0);
        cha.setNonce(TEST_VECTOR_NONCE_LOW_BYTES);
        cha.decryptWithAd(ad, out, 0, plaintext, 0, out.length);
        System.out.println("Plaintext");
        System.out.println(net.i2p.util.HexDump.dump(plaintext));
    }
****/
}
