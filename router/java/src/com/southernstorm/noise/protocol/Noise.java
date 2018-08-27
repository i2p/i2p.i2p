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

import java.lang.reflect.InvocationTargetException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.BadPaddingException;

/**
 * Utility functions for the Noise protocol library.
 */
public final class Noise {

	/**
	 * Maximum length for Noise packets.
	 */
	public static final int MAX_PACKET_LEN = 65535;
	
	/**
	 * Creates a cipher object from its Noise protocol name.
	 * 
	 * @param name The name of the cipher algorithm; e.g. "AESGCM", "ChaChaPoly", etc.
	 * 
	 * @return The cipher object if the name is recognized.
	 * 
	 * @throws NoSuchAlgorithmException The name is not recognized as a
	 * valid Noise protocol name, or there is no cryptography provider
	 * in the system that implements the algorithm.
	 */
	public static CipherState createCipher(String name) throws NoSuchAlgorithmException
	{
		if (name.equals("ChaChaPoly")) {
			return new ChaChaPolyCipherState();
		}
		throw new NoSuchAlgorithmException("Unknown Noise cipher algorithm name: " + name);
	}
	
	/**
	 * Creates a hash object from its Noise protocol name.
	 * 
	 * @param name The name of the hash algorithm; e.g. "SHA256", "BLAKE2s", etc.
	 * 
	 * @return The hash object if the name is recognized.
	 * 
	 * @throws NoSuchAlgorithmException The name is not recognized as a
	 * valid Noise protocol name, or there is no cryptography provider
	 * in the system that implements the algorithm.
	 */
	public static MessageDigest createHash(String name) throws NoSuchAlgorithmException
	{
		// Look for a JCA/JCE provider first and if that doesn't work,
		// use the fallback implementations in this library instead.
		// The only algorithm that is required to be implemented by a
		// JDK is "SHA-256", although "SHA-512" is fairly common as well.
		if (name.equals("SHA256")) {
			try {
				return MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
			}
		}
		throw new NoSuchAlgorithmException("Unknown Noise hash algorithm name: " + name);
	}

	// The rest of this class consists of internal utility functions
	// that are not part of the public API.

	/**
	 * Destroys the contents of a byte array.
	 * 
	 * @param array The array whose contents should be destroyed.
	 */
	static void destroy(byte[] array)
	{
		Arrays.fill(array, (byte)0);
	}

	/**
	 * Makes a copy of part of an array.
	 * 
	 * @param data The buffer containing the data to copy.
	 * @param offset Offset of the first byte to copy.
	 * @param length The number of bytes to copy.
	 * 
	 * @return A new array with a copy of the sub-array.
	 */
	static byte[] copySubArray(byte[] data, int offset, int length)
	{
		byte[] copy = new byte [length];
		System.arraycopy(data, offset, copy, 0, length);
		return copy;
	}
	
	/**
	 * Throws an instance of AEADBadTagException.
	 * 
	 * @throws BadPaddingException The AEAD exception.
	 * 
	 * If the underlying JDK does not have the AEADBadTagException
	 * class, then this function will instead throw an instance of
	 * the superclass BadPaddingException.
	 */
	static void throwBadTagException() throws BadPaddingException
	{
		try {
			// java since 1.7; android since API 19
			Class<?> c = Class.forName("javax.crypto.AEADBadTagException");
			throw (BadPaddingException)(c.getDeclaredConstructor().newInstance());
		} catch (ClassNotFoundException e) {
		} catch (InstantiationException e) {
		} catch (IllegalAccessException e) {
		} catch (InvocationTargetException e) {
		} catch (NoSuchMethodException e) {
		}
		throw new BadPaddingException();
	}
}
