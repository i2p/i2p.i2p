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

import java.security.GeneralSecurityException;
import java.util.Arrays;

import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.EncType;
import net.i2p.router.crypto.pqc.MLKEM;

/**
 * Implementation of the MLKEM algorithm for the Noise protocol.
 *
 * @since 0.9.67
 */
class MLKEMDHState implements DHState, Cloneable {

	private final EncType type;
	private final byte[] publicKey;
	private final byte[] privateKey;
	private int mode;
	private final KeyFactory _hdh;

	/**
	 *  Bob local/remote or Alice remote side, do not call generateKeyPair()
	 *  @param isAlice true for Bob remote side, false for Bob local side and Alice remote side
	 */
	public MLKEMDHState(boolean isAlice, String patternId)
	{
		this(isAlice, null, patternId);
	}

	/**
	 *  Alice local side
	 */
	public MLKEMDHState(KeyFactory hdh, String patternId)
	{
		this(true, hdh, patternId);
	}

	/**
	 *  Internal
	 */
	private MLKEMDHState(boolean isAlice, KeyFactory hdh, String patternId)
	{
		if (patternId.equals(HandshakeState.PATTERN_ID_IKHFS_512)) {
			type = isAlice ? EncType.MLKEM512_X25519_INT : EncType.MLKEM512_X25519_CT;
		} else if (patternId.equals(HandshakeState.PATTERN_ID_IKHFS_768)) {
			type = isAlice ? EncType.MLKEM768_X25519_INT : EncType.MLKEM768_X25519_CT;
		} else if (patternId.equals(HandshakeState.PATTERN_ID_IKHFS_1024)) {
			type = isAlice ? EncType.MLKEM1024_X25519_INT : EncType.MLKEM1024_X25519_CT;
		} else {
			throw new IllegalArgumentException("Handshake pattern is not recognized");
		}
		publicKey = new byte [type.getPubkeyLen()];
		privateKey = isAlice ? new byte [type.getPrivkeyLen()] : null;
		mode = 0;
		_hdh = hdh;
	}

	@Override
	public void destroy() {
		clearKey();
	}

	@Override
	public String getDHName() {
		return "MLKEM";
	}

	/**
	 * Note: Alice/Bob sizes are different
	 */
	@Override
	public int getPublicKeyLength() {
		return type.getPubkeyLen();
	}

	/**
	 * Note: Alice/Bob sizes are different
	 * @return 0 for Bob
	 * @deprecated
	 */
	@Deprecated
	@Override
	public int getPrivateKeyLength() {
		return type.getPrivkeyLen();
	}

	@Override
	public int getSharedKeyLength() {
		return 32;
	}

	/**
	 *  Alice local side ONLY
	 */
	@Override
	public void generateKeyPair() {
		if (_hdh == null)
			throw new IllegalStateException("Don't keygen PQ on Bob side");
		KeyPair kp = _hdh.getKeys();
		System.arraycopy(kp.getPrivate().getData(), 0, privateKey, 0, type.getPrivkeyLen());
		System.arraycopy(kp.getPublic().getData(), 0, publicKey, 0, type.getPubkeyLen());
		mode = 0x03;
	}

	@Override
	public void getPublicKey(byte[] key, int offset) {
		System.arraycopy(publicKey, 0, key, offset, type.getPubkeyLen());
	}

	@Override
	public void setPublicKey(byte[] key, int offset) {
		System.arraycopy(key, offset, publicKey, 0, type.getPubkeyLen());
		if (privateKey != null)
			Arrays.fill(privateKey, (byte)0);
		mode = 0x01;
	}

	/**
	 * @deprecated
	 */
	@Deprecated
	@Override
	public void getPrivateKey(byte[] key, int offset) {
        	throw new UnsupportedOperationException();
	}

	/**
	 * @deprecated
	 */
	@Deprecated
	@Override
	public void setPrivateKey(byte[] key, int offset) {
        	throw new UnsupportedOperationException();
	}
	
	/**
	 * @deprecated
	 */
	@Deprecated
	@Override
	public void setKeys(byte[] privkey, int privoffset, byte[] pubkey, int puboffset) {
        	throw new UnsupportedOperationException();
	}

	@Override
	public void setToNullPublicKey() {
		Arrays.fill(publicKey, (byte)0);
		if (privateKey != null)
			Arrays.fill(privateKey, (byte)0);
		mode = 0x01;
	}

	@Override
	public void clearKey() {
		Noise.destroy(publicKey);
		if (privateKey != null)
			Noise.destroy(privateKey);
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
		for (int index = 0; index < publicKey.length; ++index)
			temp |= publicKey[index];
		return temp == 0;
	}

	/**
	 *  I2P
	 */
	@Override
	public boolean hasEncodedPublicKey() {
		return false;
	}

	/**
	 * @deprecated
	 */
	@Deprecated
	@Override
	public void getEncodedPublicKey(byte[] key, int offset) {
        	throw new UnsupportedOperationException();
	}

	/**
	 *  Side effect: If we are Bob, copies the ciphertext to our public key
	 *  so it may be written out in the message.
	 *
	 *  @throws IllegalArgumentException on bad public key modulus
	 */
	@Override
	public void calculate(byte[] sharedKey, int offset, DHState publicDH) {
		if (!(publicDH instanceof MLKEMDHState))
			throw new IllegalArgumentException("Incompatible DH algorithms");
		try {
			if (hasPrivateKey()) {
				// we are Alice
				byte[] sk = MLKEM.decaps(type, ((MLKEMDHState)publicDH).publicKey, privateKey);
				System.arraycopy(sk, 0, sharedKey, offset, sk.length);
			} else if (!hasPublicKey()) {
				// we are Bob
				byte[][] rv = MLKEM.encaps(type, ((MLKEMDHState)publicDH).publicKey);
				byte[] ct = rv[0];
				byte[] sk = rv[1];
				System.arraycopy(sk, 0, sharedKey, offset, sk.length);
				setPublicKey(ct, 0);
			} else {
				throw new IllegalStateException();
			}
			//System.out.println("Calculated shared PQ key: " + net.i2p.data.Base64.encode(sharedKey, offset, 32));
		} catch (GeneralSecurityException gse) {
			throw new IllegalArgumentException(gse);
		}
	}

	@Override
	public void copyFrom(DHState other) {
		if (!(other instanceof MLKEMDHState))
			throw new IllegalStateException("Mismatched DH key objects");
		if (other == this)
			return;
		MLKEMDHState dh = (MLKEMDHState)other;
                if (dh.privateKey != null)
			System.arraycopy(dh.privateKey, 0, privateKey, 0, type.getPrivkeyLen());
                if (dh.publicKey != null)
			System.arraycopy(dh.publicKey, 0, publicKey, 0, type.getPubkeyLen());
		mode = dh.mode;
	}

	/**
	 *  I2P
	 */
	@Override
	public MLKEMDHState clone() throws CloneNotSupportedException {
		return (MLKEMDHState) super.clone();
	}
}
