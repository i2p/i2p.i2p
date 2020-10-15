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

import com.southernstorm.noise.crypto.x25519.Curve25519;

import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.KeyPair;
import net.i2p.router.crypto.ratchet.Elg2KeyPair;

/**
 * Implementation of the Curve25519 algorithm for the Noise protocol.
 */
class Curve25519DHState implements DHState, Cloneable {

	private final byte[] publicKey;
	private final byte[] privateKey;
	private int mode;
	private final KeyFactory _xdh;
	private byte[] encodedPublicKey;

	/**
	 * Constructs a new Diffie-Hellman object for Curve25519.
	 */
	public Curve25519DHState(KeyFactory xdh)
	{
		publicKey = new byte [32];
		privateKey = new byte [32];
		mode = 0;
		_xdh = xdh;
	}

	@Override
	public void destroy() {
		clearKey();
	}

	@Override
	public String getDHName() {
		return "25519";
	}

	@Override
	public int getPublicKeyLength() {
		return 32;
	}

	@Override
	public int getPrivateKeyLength() {
		return 32;
	}

	@Override
	public int getSharedKeyLength() {
		return 32;
	}

	@Override
	public void generateKeyPair() {
		KeyPair kp = _xdh.getKeys();
		System.arraycopy(kp.getPrivate().getData(), 0, privateKey, 0, 32);
		System.arraycopy(kp.getPublic().getData(), 0, publicKey, 0, 32);
		if (kp instanceof Elg2KeyPair) {
			Elg2KeyPair ekp = (Elg2KeyPair) kp;
			encodedPublicKey = new byte[32];
			System.arraycopy(ekp.getEncoded(), 0, encodedPublicKey, 0, 32);
		}
		mode = 0x03;
	}

	@Override
	public void getPublicKey(byte[] key, int offset) {
		System.arraycopy(publicKey, 0, key, offset, 32);
	}

	@Override
	public void setPublicKey(byte[] key, int offset) {
		System.arraycopy(key, offset, publicKey, 0, 32);
		Arrays.fill(privateKey, (byte)0);
		mode = 0x01;
	}

	@Override
	public void getPrivateKey(byte[] key, int offset) {
		System.arraycopy(privateKey, 0, key, offset, 32);
	}

	/**
	 * @deprecated use setKeys()
	 */
	@Deprecated
	@Override
	public void setPrivateKey(byte[] key, int offset) {
		System.arraycopy(key, offset, privateKey, 0, 32);
		Curve25519.eval(publicKey, 0, privateKey, null);
		mode = 0x03;
	}
	
	/**
	 * Sets the private and public keys for this object.
	 * I2P for efficiency, since setPrivateKey() calculates the public key
	 * and overwrites it.
	 * Does NOT check that the two keys match.
	 * 
	 * @param privkey The buffer containing the private key.
	 * @param privoffset The first offset in the buffer that contains the key.
	 * @param pubkey The buffer containing the public key.
	 * @param puboffset The first offset in the buffer that contains the key.
	 * @since 0.9.48
	 */
	@Override
	public void setKeys(byte[] privkey, int privoffset, byte[] pubkey, int puboffset) {
		System.arraycopy(privkey, privoffset, privateKey, 0, 32);
		System.arraycopy(pubkey, puboffset, publicKey, 0, 32);
		mode = 0x03;
	}

	@Override
	public void setToNullPublicKey() {
		Arrays.fill(publicKey, (byte)0);
		Arrays.fill(privateKey, (byte)0);
		if (encodedPublicKey != null) {
			Arrays.fill(encodedPublicKey, (byte)0);
			encodedPublicKey = null;
		}
		mode = 0x01;
	}

	@Override
	public void clearKey() {
		Noise.destroy(publicKey);
		Noise.destroy(privateKey);
		if (encodedPublicKey != null) {
			Noise.destroy(encodedPublicKey);
			encodedPublicKey = null;
		}
		mode = 0;
	}

	@Override
	public boolean hasPublicKey() {
		return (mode & 0x01) != 0;
	}

	@Override
	public boolean hasPrivateKey() {
		return (mode & 0x02) != 0;
	}

	@Override
	public boolean isNullPublicKey() {
		if ((mode & 0x01) == 0)
			return false;
		int temp = 0;
		for (int index = 0; index < 32; ++index)
			temp |= publicKey[index];
		return temp == 0;
	}

	/**
	 *  I2P
	 *  @since 0.9.44
	 */
	@Override
	public boolean hasEncodedPublicKey() {
		return encodedPublicKey != null;
	}

	/**
	 *  I2P
	 *  @since 0.9.44
	 */
	@Override
	public void getEncodedPublicKey(byte[] key, int offset) {
		if (encodedPublicKey == null)
			throw new IllegalStateException();
		System.arraycopy(encodedPublicKey, 0, key, offset, 32);
	}

	@Override
	public void calculate(byte[] sharedKey, int offset, DHState publicDH) {
		if (!(publicDH instanceof Curve25519DHState))
			throw new IllegalArgumentException("Incompatible DH algorithms");
		Curve25519.eval(sharedKey, offset, privateKey, ((Curve25519DHState)publicDH).publicKey);
	}

	@Override
	public void copyFrom(DHState other) {
		if (!(other instanceof Curve25519DHState))
			throw new IllegalStateException("Mismatched DH key objects");
		if (other == this)
			return;
		Curve25519DHState dh = (Curve25519DHState)other;
		System.arraycopy(dh.privateKey, 0, privateKey, 0, 32);
		System.arraycopy(dh.publicKey, 0, publicKey, 0, 32);
		mode = dh.mode;
	}

	/**
	 *  I2P
	 *  @since 0.9.44
	 */
	@Override
	public Curve25519DHState clone() throws CloneNotSupportedException {
		return (Curve25519DHState) super.clone();
	}
}
