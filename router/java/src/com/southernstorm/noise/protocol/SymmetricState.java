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

import java.io.UnsupportedEncodingException;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;

/**
 * Symmetric state for helping manage a Noise handshake.
 */
class SymmetricState implements Destroyable {
	
	// precalculated hash of the Noise name
	private static final byte[] INIT_HASH;

	static {
		byte[] protocolNameBytes;
		try {
			protocolNameBytes = HandshakeState.protocolName.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			// If UTF-8 is not supported, then we are definitely in trouble!
			throw new UnsupportedOperationException("UTF-8 encoding is not supported");
		}
		INIT_HASH = new byte[32];
		if (protocolNameBytes.length <= 32) {
			System.arraycopy(protocolNameBytes, 0, INIT_HASH, 0, protocolNameBytes.length);
			Arrays.fill(INIT_HASH, protocolNameBytes.length, 32, (byte)0);
		} else {
			try {
				MessageDigest hash = Noise.createHash("SHA256");
				hash.update(protocolNameBytes, 0, protocolNameBytes.length);
				hash.digest(INIT_HASH, 0, 32);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private final CipherState cipher;
	private final MessageDigest hash;
	private final byte[] ck;
	private final byte[] h;
	private final byte[] prev_h;

	/**
	 * Constructs a new symmetric state object.
	 * Noise protocol name is hardcoded.
	 * 
	 * @param cipherName The name of the cipher within protocolName.
	 * @param hashName The name of the hash within protocolName.
	 * 
	 * @throws NoSuchAlgorithmException The cipher or hash algorithm in the
	 * protocol name is not supported.
	 */
	public SymmetricState(String cipherName, String hashName) throws NoSuchAlgorithmException
	{
		cipher = Noise.createCipher(cipherName);
		hash = Noise.createHash(hashName);
		int hashLength = hash.getDigestLength();
		ck = new byte [hashLength];
		h = new byte [hashLength];
		prev_h = new byte [hashLength];
		
		System.arraycopy(INIT_HASH, 0, h, 0, hashLength);
		System.arraycopy(h, 0, ck, 0, hashLength);
	}

	/**
	 * Gets the name of the Noise protocol. 
	 *
	 * @return The protocol name.
	 */
	public String getProtocolName()
	{
		return HandshakeState.protocolName;
	}
	
	/**
	 * Gets the length of MAC values in the current state.
	 * 
	 * @return The length of the MAC value for the underlying cipher
	 * or zero if the cipher has not yet been initialized with a key.
	 */
	public int getMACLength()
	{
		return cipher.getMACLength();
	}

	/**
	 * Mixes data into the chaining key.
	 * 
	 * @param data The buffer containing the data to mix in.
	 * @param offset The offset of the first data byte to mix in.
	 * @param length The number of bytes to mix in.
	 */
	public void mixKey(byte[] data, int offset, int length)
	{
		int keyLength = cipher.getKeyLength();
		byte[] tempKey = new byte [keyLength];
		try {
			hkdf(ck, 0, ck.length, data, offset, length, ck, 0, ck.length, tempKey, 0, keyLength);
			cipher.initializeKey(tempKey, 0);
		} finally {
			Noise.destroy(tempKey);
		}
	}

	/**
	 * Mixes data into the handshake hash.
	 * 
	 * @param data The buffer containing the data to mix in.
	 * @param offset The offset of the first data byte to mix in.
	 * @param length The number of bytes to mix in.
	 */
	public void mixHash(byte[] data, int offset, int length)
	{
		hashTwo(h, 0, h.length, data, offset, length, h, 0, h.length);
	}

	/**
	 * Mixes a pre-shared key into the chaining key and handshake hash.
	 * 
	 * @param key The pre-shared key value.
	 */
	public void mixPreSharedKey(byte[] key)
	{
		byte[] temp = new byte [hash.getDigestLength()];
		try {
			hkdf(ck, 0, ck.length, key, 0, key.length, ck, 0, ck.length, temp, 0, temp.length);
			mixHash(temp, 0, temp.length);
		} finally {
			Noise.destroy(temp);
		}
	}

	/**
	 * Mixes a pre-supplied public key into the handshake hash.
	 * 
	 * @param dh The object containing the public key.
	 */
	public void mixPublicKey(DHState dh)
	{
		byte[] temp = new byte [dh.getPublicKeyLength()];
		try {
			dh.getPublicKey(temp, 0);
			mixHash(temp, 0, temp.length);
		} finally {
			Noise.destroy(temp);
		}
	}

	/**
	 * Mixes a pre-supplied public key into the chaining key.
	 * 
	 * @param dh The object containing the public key.
	 */
	public void mixPublicKeyIntoCK(DHState dh)
	{
		byte[] temp = new byte [dh.getPublicKeyLength()];
		try {
			dh.getPublicKey(temp, 0);
			mixKey(temp, 0, temp.length);
		} finally {
			Noise.destroy(temp);
		}
	}

	/**
	 * Encrypts a block of plaintext and mixes the ciphertext into the handshake hash.
	 * 
	 * @param plaintext The buffer containing the plaintext to encrypt.
	 * @param plaintextOffset The offset within the plaintext buffer of the
	 * first byte or plaintext data.
	 * @param ciphertext The buffer to place the ciphertext in.  This can
	 * be the same as the plaintext buffer.
	 * @param ciphertextOffset The first offset within the ciphertext buffer
	 * to place the ciphertext and the MAC tag.
	 * @param length The length of the plaintext.
	 * @return The length of the ciphertext plus the MAC tag.
	 * 
	 * @throws ShortBufferException There is not enough space in the
	 * ciphertext buffer for the encrypted data plus MAC value.
	 * 
	 * The plaintext and ciphertext buffers can be the same for in-place
	 * encryption.  In that case, plaintextOffset must be identical to
	 * ciphertextOffset.
	 * 
	 * There must be enough space in the ciphertext buffer to accomodate
	 * length + getMACLength() bytes of data starting at ciphertextOffset.
	 */
	public int encryptAndHash(byte[] plaintext, int plaintextOffset, byte[] ciphertext, int ciphertextOffset, int length) throws ShortBufferException
	{
		int ciphertextLength = cipher.encryptWithAd(h, plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
		mixHash(ciphertext, ciphertextOffset, ciphertextLength);
		return ciphertextLength;
	}

	/**
	 * Decrypts a block of ciphertext and mixes it into the handshake hash.
	 * 
	 * @param ciphertext The buffer containing the ciphertext to decrypt.
	 * @param ciphertextOffset The offset within the ciphertext buffer of
	 * the first byte of ciphertext data.
	 * @param plaintext The buffer to place the plaintext in.  This can be
	 * the same as the ciphertext buffer.
	 * @param plaintextOffset The first offset within the plaintext buffer
	 * to place the plaintext.
	 * @param length The length of the incoming ciphertext plus the MAC tag.
	 * @return The length of the plaintext with the MAC tag stripped off.
	 * 
	 * @throws ShortBufferException There is not enough space in the plaintext
	 * buffer for the decrypted data.
	 * 
	 * @throws BadPaddingException The MAC value failed to verify.
	 * 
	 * The plaintext and ciphertext buffers can be the same for in-place
	 * decryption.  In that case, ciphertextOffset must be identical to
	 * plaintextOffset.
	 */
	public int decryptAndHash(byte[] ciphertext, int ciphertextOffset, byte[] plaintext, int plaintextOffset, int length) throws ShortBufferException, BadPaddingException
	{
		System.arraycopy(h, 0, prev_h, 0, h.length);
		mixHash(ciphertext, ciphertextOffset, length);
		return cipher.decryptWithAd(prev_h, ciphertext, ciphertextOffset, plaintext, plaintextOffset, length);
	}

	/**
	 * Splits the symmetric state into two ciphers for session encryption.
	 * 
	 * @return The pair of ciphers for sending and receiving.
	 */
	public CipherStatePair split()
	{
		return split(new byte[0], 0, 0);
	}
	
	/**
	 * Splits the symmetric state into two ciphers for session encryption,
	 * and optionally mixes in a secondary symmetric key.
	 * 
	 * @param secondaryKey The buffer containing the secondary key.
	 * @param offset The offset of the first secondary key byte.
	 * @param length The length of the secondary key in bytes, which
	 * must be either 0 or 32.
	 * @return The pair of ciphers for sending and receiving.
	 * 
	 * @throws IllegalArgumentException The length is not 0 or 32.
	 */
	public CipherStatePair split(byte[] secondaryKey, int offset, int length)
	{
		if (length != 0 && length != 32)
			throw new IllegalArgumentException("Secondary keys must be 0 or 32 bytes in length");
		int keyLength = cipher.getKeyLength();
		byte[] k1 = new byte [keyLength];
		byte[] k2 = new byte [keyLength];
		try {
			hkdf(ck, 0, ck.length, secondaryKey, offset, length, k1, 0, k1.length, k2, 0, k2.length);
			CipherState c1 = null;
			CipherState c2 = null;
			CipherStatePair pair = null;
			try {
				c1 = cipher.fork(k1, 0);
				c2 = cipher.fork(k2, 0);
				pair = new CipherStatePair(c1, c2);
			} finally {
				if (c1 == null || c2 == null || pair == null) {
					// Could not create some of the objects.  Clean up the others
					// to avoid accidental leakage of k1 or k2.
					if (c1 != null)
						c1.destroy();
					if (c2 != null)
						c2.destroy();
					pair = null;
				}
			}
			return pair;
		} finally {
			Noise.destroy(k1);
			Noise.destroy(k2);
		}
	}

	/**
	 * Gets the current value of the handshake hash.
	 * 
	 * @return The handshake hash.  This must not be modified by the caller.
	 * 
	 * The handshake hash value is only of use to the application after
	 * split() has been called.
	 */
	public byte[] getHandshakeHash()
	{
		return h;
	}

	@Override
	public void destroy() {
		cipher.destroy();
		hash.reset();
		Noise.destroy(ck);
		Noise.destroy(h);
		Noise.destroy(prev_h);
	}

	/**
	 * Hashes a single data buffer.
	 * 
	 * @param data The buffer containing the data to hash.
	 * @param offset Offset into the data buffer of the first byte to hash.
	 * @param length Length of the data to be hashed.
	 * @param output The buffer to receive the output hash value.
	 * @param outputOffset Offset into the output buffer to place the hash value.
	 * @param outputLength The length of the hash output.
	 * 
	 * The output buffer can be the same as the input data buffer.
	 */
	private void hashOne(byte[] data, int offset, int length, byte[] output, int outputOffset, int outputLength)
	{
		hash.reset();
		hash.update(data, offset, length);
		try {
			hash.digest(output, outputOffset, outputLength);
		} catch (DigestException e) {
			Arrays.fill(output, outputOffset, outputLength, (byte)0);
		}
	}

	/**
	 * Hashes two data buffers.
	 * 
	 * @param data1 The buffer containing the first data to hash.
	 * @param offset1 Offset into the first data buffer of the first byte to hash.
	 * @param length1 Length of the first data to be hashed.
	 * @param data2 The buffer containing the second data to hash.
	 * @param offset2 Offset into the second data buffer of the first byte to hash.
	 * @param length2 Length of the second data to be hashed.
	 * @param output The buffer to receive the output hash value.
	 * @param outputOffset Offset into the output buffer to place the hash value.
	 * @param outputLength The length of the hash output.
	 * 
	 * The output buffer can be same as either of the input buffers.
	 */
	private void hashTwo(byte[] data1, int offset1, int length1,
			     		 byte[] data2, int offset2, int length2,
			     		 byte[] output, int outputOffset, int outputLength)
	{
		hash.reset();
		hash.update(data1, offset1, length1);
		hash.update(data2, offset2, length2);
		try {
			hash.digest(output, outputOffset, outputLength);
		} catch (DigestException e) {
			Arrays.fill(output, outputOffset, outputLength, (byte)0);
		}
	}

	/**
	 * Computes a HMAC value using key and data values.
	 * 
	 * @param key The buffer that contains the key.
	 * @param keyOffset The offset of the key in the key buffer.
	 * @param keyLength The length of the key in bytes.
	 * @param data The buffer that contains the data.
	 * @param dataOffset The offset of the data in the data buffer.
	 * @param dataLength The length of the data in bytes.
	 * @param output The output buffer to place the HMAC value in.
	 * @param outputOffset Offset into the output buffer for the HMAC value.
	 * @param outputLength The length of the HMAC output.
	 */
	private void hmac(byte[] key, int keyOffset, int keyLength,
					  byte[] data, int dataOffset, int dataLength,
					  byte[] output, int outputOffset, int outputLength)
	{
		// In all of the algorithms of interest to us, the block length
		// is twice the size of the hash length.
		int hashLength = hash.getDigestLength();
		int blockLength = hashLength * 2;
		byte[] block = new byte [blockLength];
		int index;
		try {
			if (keyLength <= blockLength) {
				System.arraycopy(key, keyOffset, block, 0, keyLength);
				Arrays.fill(block, keyLength, blockLength, (byte)0);
			} else {
				hash.reset();
				hash.update(key, keyOffset, keyLength);
				hash.digest(block, 0, hashLength);
				Arrays.fill(block, hashLength, blockLength, (byte)0);
			}
			for (index = 0; index < blockLength; ++index)
				block[index] ^= (byte)0x36;
			hash.reset();
			hash.update(block, 0, blockLength);
			hash.update(data, dataOffset, dataLength);
			hash.digest(output, outputOffset, hashLength);
			for (index = 0; index < blockLength; ++index)
				block[index] ^= (byte)(0x36 ^ 0x5C);
			hash.reset();
			hash.update(block, 0, blockLength);
			hash.update(output, outputOffset, hashLength);
			hash.digest(output, outputOffset, outputLength);
		} catch (DigestException e) {
			Arrays.fill(output, outputOffset, outputLength, (byte)0);
		} finally {
			Noise.destroy(block);
		}
	}

	/**
	 * Computes a HKDF value.
	 * 
	 * @param key The buffer that contains the key.
	 * @param keyOffset The offset of the key in the key buffer.
	 * @param keyLength The length of the key in bytes.
	 * @param data The buffer that contains the data.
	 * @param dataOffset The offset of the data in the data buffer.
	 * @param dataLength The length of the data in bytes.
	 * @param output1 The first output buffer.
	 * @param output1Offset Offset into the first output buffer.
	 * @param output1Length Length of the first output which can be
	 * less than the hash length.
	 * @param output2 The second output buffer.
	 * @param output2Offset Offset into the second output buffer.
	 * @param output2Length Length of the second output which can be
	 * less than the hash length.
	 */
	private void hkdf(byte[] key, int keyOffset, int keyLength,
			  		  byte[] data, int dataOffset, int dataLength,
			  		  byte[] output1, int output1Offset, int output1Length,
			  		  byte[] output2, int output2Offset, int output2Length)
	{
		int hashLength = hash.getDigestLength();
		byte[] tempKey = new byte [hashLength];
		byte[] tempHash = new byte [hashLength + 1];
		try {
			hmac(key, keyOffset, keyLength, data, dataOffset, dataLength, tempKey, 0, hashLength);
			tempHash[0] = (byte)0x01;
			hmac(tempKey, 0, hashLength, tempHash, 0, 1, tempHash, 0, hashLength);
			System.arraycopy(tempHash, 0, output1, output1Offset, output1Length);
			tempHash[hashLength] = (byte)0x02;
			hmac(tempKey, 0, hashLength, tempHash, 0, hashLength + 1, tempHash, 0, hashLength);
			System.arraycopy(tempHash, 0, output2, output2Offset, output2Length);
		} finally {
			Noise.destroy(tempKey);
			Noise.destroy(tempHash);
		}
	}

	/**
	 *  I2P for getting chaining key for siphash calculation
	 *  @return a copy
	 */
	public byte[] getChainingKey() {
		byte[] rv = new byte[ck.length];
		System.arraycopy(ck, 0, rv, 0, ck.length);
		return rv;
	}

	/**
	 *  I2P debug
	 */
	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder();
		buf.append("  Symmetric State:\n" +
		           "    ck: ");
		buf.append(net.i2p.data.Base64.encode(ck));
		buf.append("\n" +
		           "    h:  ");
		buf.append(net.i2p.data.Base64.encode(h));
		buf.append('\n');
		buf.append(cipher.toString());
		return buf.toString();
	}

/****
    private static final int LENGTH = 33;

    public static void main(String args[]) throws Exception {
        net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
        byte[] rand = new byte[32];
        byte[] data = new byte[LENGTH];
        byte[] out = new byte[32];
        System.out.println("Warmup");
        int RUNS = 25000;
        SymmetricState ss = new SymmetricState("ChaChaPoly", "SHA256");
        for (int i = 0; i < RUNS; i++) {
            ctx.random().nextBytes(rand);
            ctx.random().nextBytes(data);
            ss.hmac(rand, 0, 32, data, 0, LENGTH, out, 0, 32);
        }
        System.out.println("Start");
        RUNS = 500000;
        long start = System.currentTimeMillis();
        for (int i = 0; i < RUNS; i++) {
            ss.hmac(rand, 0, 32, data, 0, LENGTH, out, 0, 32);
        }
        long time = System.currentTimeMillis() - start;
        System.out.println("Time for " + RUNS + " HMAC-SHA256 computations:");
        System.out.println("Noise time (ms): " + time);
    }
****/
}
