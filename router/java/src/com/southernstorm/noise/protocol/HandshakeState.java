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

import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;

import net.i2p.router.transport.crypto.X25519KeyFactory;

/**
 * Interface to a Noise handshake.
 */
public class HandshakeState implements Destroyable {

	private final SymmetricState symmetric;
	private final boolean isInitiator;
	private DHState localKeyPair;
	private DHState localEphemeral;
	private DHState remotePublicKey;
	private DHState remoteEphemeral;
	private int action;
	private final int requirements;
	private int patternIndex;
        // not supported
	private static final byte[] prologue = null;

	/**
	 * Enumerated value that indicates that the handshake object
	 * is handling the initiator role.
	 */
	public static final int INITIATOR = 1;

	/**
	 * Enumerated value that indicates that the handshake object
	 * is handling the responder role.
	 */
	public static final int RESPONDER = 2;

	/**
	 * No action is required of the application yet because the
	 * handshake has not started.
	 */
	public static final int NO_ACTION = 0;
	
	/**
	 * The HandshakeState expects the application to write the
	 * next message payload for the handshake.
	 */
	public static final int WRITE_MESSAGE = 1;
	
	/**
	 * The HandshakeState expects the application to read the
	 * next message payload from the handshake.
	 */
	public static final int READ_MESSAGE = 2;
	
	/**
	 * The handshake has failed due to some kind of error.
	 */
	public static final int FAILED = 3;
	
	/**
	 * The handshake is over and the application is expected to call
	 * split() and begin data session communications.
	 */
	public static final int SPLIT = 4;

	/**
	 * The handshake is complete and the data session ciphers
	 * have been split() out successfully.
	 */
	public static final int COMPLETE = 5;
	
	/**
	 * Local static keypair is required for the handshake.
	 */
	private static final int LOCAL_REQUIRED = 0x01;
	
	/**
	 * Remote static keypai is required for the handshake.
	 */
	private static final int REMOTE_REQUIRED = 0x02;
	
	/**
	 * Pre-shared key is required for the handshake.
	 */
	private static final int PSK_REQUIRED = 0x04;
	
	/**
	 * Ephemeral key for fallback pre-message has been provided.
	 */
	private static final int FALLBACK_PREMSG = 0x08;
	
	/**
	 * The local public key is part of the pre-message.
	 */
	private static final int LOCAL_PREMSG = 0x10;

	/**
	 * The remote public key is part of the pre-message.
	 */
	private static final int REMOTE_PREMSG = 0x20;

	/**
	 * Fallback is possible from this pattern (two-way, ends in "K").
	 */
	private static final int FALLBACK_POSSIBLE = 0x40;

	public static final String protocolName = "Noise_XKaesobfse+hs2+hs3_25519_ChaChaPoly_SHA256";
	private static final String prefix;
	private static final String patternId;
	private static String dh;
	private static final String cipher;
	private static final String hash;
	private static final short[] pattern;

	static {
		// Parse the protocol name into its components.
		String[] components = protocolName.split("_");
		if (components.length != 5)
			throw new IllegalArgumentException("Protocol name must have 5 components");
		prefix = components[0];
		patternId = components[1].substring(0, 2);
		dh = components[2];
		cipher = components[3];
		hash = components[4];
		if (!prefix.equals("Noise") && !prefix.equals("NoisePSK"))
			throw new IllegalArgumentException("Prefix must be Noise or NoisePSK");
		pattern = Pattern.lookup(patternId);
		if (pattern == null)
			throw new IllegalArgumentException("Handshake pattern is not recognized");
		if (!dh.equals("25519"))
			throw new IllegalArgumentException("Unknown Noise DH algorithm name: " + dh);
	}

	/**
	 * Creates a new Noise handshake.
	 * Noise protocol name is hardcoded.
	 * 
	 * @param role The role, HandshakeState.INITIATOR or HandshakeState.RESPONDER.
	 * @param xdh The key pair factory for ephemeral keys
	 * 
	 * @throws IllegalArgumentException The protocolName is not
	 * formatted correctly, or the role is not recognized.
	 * 
	 * @throws NoSuchAlgorithmException One of the cryptographic algorithms
	 * that is specified in the protocolName is not supported.
	 */
	public HandshakeState(int role, X25519KeyFactory xdh) throws NoSuchAlgorithmException
	{
		short flags = pattern[0];
		int extraReqs = 0;
		if ((flags & Pattern.FLAG_REMOTE_REQUIRED) != 0 && patternId.length() > 1)
			extraReqs |= FALLBACK_POSSIBLE;
		if (role == RESPONDER) {
			// Reverse the pattern flags so that the responder is "local".
			flags = Pattern.reverseFlags(flags);
		}

		// Check that the role is correctly specified.
		if (role != INITIATOR && role != RESPONDER)
			throw new IllegalArgumentException("Role must be initiator or responder");

		// Initialize this object.  This will also create the cipher and hash objects.
		symmetric = new SymmetricState(cipher, hash);
		isInitiator = (role == INITIATOR);
		action = NO_ACTION;
		requirements = extraReqs | computeRequirements(flags, prefix, role, false);
		patternIndex = 1;
		
		// Create the DH objects that we will need later.
		if ((flags & Pattern.FLAG_LOCAL_STATIC) != 0)
			localKeyPair = new Curve25519DHState(xdh);
		if ((flags & Pattern.FLAG_LOCAL_EPHEMERAL) != 0)
			localEphemeral = new Curve25519DHState(xdh);
		if ((flags & Pattern.FLAG_REMOTE_STATIC) != 0)
			remotePublicKey = new Curve25519DHState(xdh);
		if ((flags & Pattern.FLAG_REMOTE_EPHEMERAL) != 0)
			remoteEphemeral = new Curve25519DHState(xdh);
		
	}

	/**
	 * Gets the name of the Noise protocol. 
	 *
	 * @return The protocol name.
	 */
	public String getProtocolName()
	{
		return symmetric.getProtocolName();
	}
	
	/**
	 * Gets the role for this handshake.
	 * 
	 * @return The role, HandshakeState.INITIATOR or HandshakeState.RESPONDER.
	 */
	public int getRole()
	{
		return isInitiator ? INITIATOR : RESPONDER;
	}
	
	/**
	 * Gets the keypair object for the local static key.
	 * 
	 * @return The keypair, or null if a local static key is not required.
	 */
	public DHState getLocalKeyPair()
	{
		return localKeyPair;
	}

	/**
	 * Determine if this handshake requires a local static key.
	 * 
	 * @return true if a local static key is needed; false if not.
	 * 
	 * If the local static key has already been set, then this function
	 * will return false.
	 */
	public boolean needsLocalKeyPair()
	{
		if (localKeyPair != null)
			return !localKeyPair.hasPrivateKey();
		else
			return false;
	}

	/**
	 * Determine if this handshake has already been configured
	 * with a local static key.
	 * 
	 * @return true if the local static key has been configured;
	 * false if not.
	 */
	public boolean hasLocalKeyPair()
	{
		if (localKeyPair != null)
			return localKeyPair.hasPrivateKey();
		else
			return false;
	}
	
	/**
	 * Gets the public key object for the remote static key.
	 * 
	 * @return The public key, or null if a remote static key
	 * is not required.
	 */
	public DHState getRemotePublicKey()
	{
		return remotePublicKey;
	}
	
	/**
	 * Determine if this handshake requires a remote static key.
	 * 
	 * @return true if a remote static key is needed; false if not.
	 * 
	 * If the remote static key has already been set, then this function
	 * will return false.
	 */
	public boolean needsRemotePublicKey()
	{
		if (remotePublicKey != null)
			return !remotePublicKey.hasPublicKey();
		else
			return false;
	}
	
	/**
	 * Determine if this handshake has already been configured
	 * with a remote static key.
	 * 
	 * @return true if the remote static key has been configured;
	 * false if not.
	 */
	public boolean hasRemotePublicKey()
	{
		if (remotePublicKey != null)
			return remotePublicKey.hasPublicKey();
		else
			return false;
	}

	// Empty value for when the prologue is not supplied.
	private static final byte[] emptyPrologue = new byte [0];

	/**
	 * Starts the handshake running.
	 * 
	 * This function is called after all of the handshake parameters have been
	 * provided to the HandshakeState object.  This function should be followed
	 * by calls to writeMessage() or readMessage() to process the handshake
	 * messages.  The getAction() function indicates the action to take next.
	 * 
	 * @throws IllegalStateException The handshake has already started, or one or
	 * more of the required parameters has not been supplied.
	 * 
	 * @throws UnsupportedOperationException An attempt was made to start a
	 * fallback handshake pattern without first calling fallback() on a
	 * previous handshake.
	 * 
	 * @see #getAction()
	 * @see #writeMessage(byte[], int, byte[], int, int)
	 * @see #readMessage(byte[], int, int, byte[], int)
	 * see #fallback()
	 */
	public void start()
	{
		if (action != NO_ACTION) {
			throw new IllegalStateException
				("Handshake has already started; cannot start again");
		}
		if ((pattern[0] & Pattern.FLAG_REMOTE_EPHEM_REQ) != 0 &&
				(requirements & FALLBACK_PREMSG) == 0) {
			throw new UnsupportedOperationException
				("Cannot start a fallback pattern");
		}
		
		// Check that we have satisfied all of the pattern requirements.
		if ((requirements & LOCAL_REQUIRED) != 0) {
			if (localKeyPair == null || !localKeyPair.hasPrivateKey())
				throw new IllegalStateException("Local static key required");
		}
		if ((requirements & REMOTE_REQUIRED) != 0) {
			if (remotePublicKey == null || !remotePublicKey.hasPublicKey())
				throw new IllegalStateException("Remote static key required");
		}
		
		// Hash the prologue value.
		if (prologue != null)
			symmetric.mixHash(prologue, 0, prologue.length);
		else
			symmetric.mixHash(emptyPrologue, 0, 0);
		
		// Mix the pre-supplied public keys into the handshake hash.
		if (isInitiator) {
			if ((requirements & LOCAL_PREMSG) != 0)
				symmetric.mixPublicKey(localKeyPair);
			if ((requirements & FALLBACK_PREMSG) != 0) {
				symmetric.mixPublicKey(remoteEphemeral);
			}
			if ((requirements & REMOTE_PREMSG) != 0)
				symmetric.mixPublicKey(remotePublicKey);
		} else {
			if ((requirements & REMOTE_PREMSG) != 0)
				symmetric.mixPublicKey(remotePublicKey);
			if ((requirements & FALLBACK_PREMSG) != 0) {
				symmetric.mixPublicKey(localEphemeral);
			}
			if ((requirements & LOCAL_PREMSG) != 0)
				symmetric.mixPublicKey(localKeyPair);
		}
		
		// The handshake has officially started - set the first action.
		if (isInitiator)
			action = WRITE_MESSAGE;
		else
			action = READ_MESSAGE;
	}

	/**
	 * Gets the next action that the application should perform for
	 * the handshake part of the protocol.
	 * 
	 * @return One of HandshakeState.NO_ACTION, HandshakeState.WRITE_MESSAGE,
	 * HandshakeState.READ_MESSAGE, HandshakeState.SPLIT, or
	 * HandshakeState.FAILED.
	 */
	public int getAction()
	{
		return action;
	}

	/**
	 * Mixes the result of a Diffie-Hellman calculation into the chaining key.
	 * 
	 * @param local Local private key object.
	 * @param remote Remote public key object.
	 */
	private void mixDH(DHState local, DHState remote)
	{
		if (local == null || remote == null)
			throw new IllegalStateException("Pattern definition error");
		int len = local.getSharedKeyLength();
		byte[] shared = new byte [len];
		try {
			local.calculate(shared, 0, remote);
			symmetric.mixKey(shared, 0, len);
		} finally {
			Noise.destroy(shared);
		}
	}

	/**
	 * Writes a message payload during the handshake.
	 * 
	 * @param message The buffer that will be populated with the
	 * handshake packet to be written to the transport.
	 * @param messageOffset First offset within the message buffer
	 * to be populated.
	 * @param payload Buffer containing the payload to add to the
	 * handshake message; can be null if there is no payload.
	 * @param payloadOffset Offset into the payload buffer of the
	 * first payload buffer.
	 * @param payloadLength Length of the payload in bytes.
	 * 
	 * @return The length of the data written to the message buffer.
	 * 
	 * @throws IllegalStateException The action is not WRITE_MESSAGE.
	 * 
	 * @throws IllegalArgumentException The payload is null, but
	 * payloadOffset or payloadLength is non-zero.
	 * 
	 * @throws ShortBufferException The message buffer does not have
	 * enough space for the handshake message. 
	 * 
	 * @see #getAction()
	 * @see #readMessage(byte[], int, int, byte[], int)
	 */
	public int writeMessage(byte[] message, int messageOffset, byte[] payload, int payloadOffset, int payloadLength) throws ShortBufferException
	{
		int messagePosn = messageOffset;
		boolean success = false;

		// Validate the parameters and state.
		if (action != WRITE_MESSAGE) {
			throw new IllegalStateException
				("Handshake state does not allow writing messages");
		}
		if (payload == null && (payloadOffset != 0 || payloadLength != 0)) {
			throw new IllegalArgumentException("Invalid payload argument");
		}
		if (messageOffset > message.length) {
			throw new ShortBufferException();
		}
		
		// Format the message.
		try {
			// Process tokens until the direction changes or the patten ends.
			for (;;) {
				if (patternIndex >= pattern.length) {
					// The pattern has finished, so the next action is "split".
					action = SPLIT;
					break;
				}
				short token = pattern[patternIndex++];
				if (token == Pattern.FLIP_DIR) {
					// Change directions, so this message is complete and the
					// next action is "read message".
					action = READ_MESSAGE;
					break;
				}
				int space = message.length - messagePosn;
				int len, macLen;
				switch (token) {
					case Pattern.E:
					{
						// Generate a local ephemeral keypair and add the public
						// key to the message.  If we are running fixed vector tests,
						// then the ephemeral key may have already been provided.
						if (localEphemeral == null)
							throw new IllegalStateException("Pattern definition error");
						localEphemeral.generateKeyPair();
						len = localEphemeral.getPublicKeyLength();
						if (space < len)
							throw new ShortBufferException();
						localEphemeral.getPublicKey(message, messagePosn);
						symmetric.mixHash(message, messagePosn, len);

						// If the protocol is using pre-shared keys, then also mix
						// the local ephemeral key into the chaining key.
						messagePosn += len;
					}
					break;

					case Pattern.S:
					{
						// Encrypt the local static public key and add it to the message.
						if (localKeyPair == null)
							throw new IllegalStateException("Pattern definition error");
						len = localKeyPair.getPublicKeyLength();
						macLen = symmetric.getMACLength();
						if (space < (len + macLen))
							throw new ShortBufferException();
						localKeyPair.getPublicKey(message, messagePosn);
						messagePosn += symmetric.encryptAndHash(message, messagePosn, message, messagePosn, len);
					}
					break;

					case Pattern.EE:
					{
						// DH operation with initiator and responder ephemeral keys.
						mixDH(localEphemeral, remoteEphemeral);
					}
					break;

					case Pattern.ES:
					{
						// DH operation with initiator ephemeral and responder static keys.
						if (isInitiator)
							mixDH(localEphemeral, remotePublicKey);
						else
							mixDH(localKeyPair, remoteEphemeral);
					}
					break;

					case Pattern.SE:
					{
						// DH operation with initiator static and responder ephemeral keys.
						if (isInitiator)
							mixDH(localKeyPair, remoteEphemeral);
						else
							mixDH(localEphemeral, remotePublicKey);
					}
					break;

					default:
					{
						// Unknown token code.  Abort.
						throw new IllegalStateException("Unknown handshake token " + Integer.toString(token));
					}
				}
			}
			
			// Add the payload to the message buffer and encrypt it.
			if (payload != null)
				messagePosn += symmetric.encryptAndHash(payload, payloadOffset, message, messagePosn, payloadLength);
			else
				messagePosn += symmetric.encryptAndHash(message, messagePosn, message, messagePosn, 0);
			success = true;
		} finally {
			// If we failed, then clear any sensitive data that may have
			// already been written to the message buffer.
			if (!success) {
				Arrays.fill(message, messageOffset, message.length - messageOffset, (byte)0);
				action = FAILED;
			}
		}
		return messagePosn - messageOffset;
	}

	/**
	 * Reads a message payload during the handshake.
	 * 
	 * @param message Buffer containing the incoming handshake
	 * that was read from the transport.
	 * @param messageOffset Offset of the first message byte.
	 * @param messageLength The length of the incoming message.
	 * @param payload Buffer that will be populated with the message payload.
	 * @param payloadOffset Offset of the first byte in the
	 * payload buffer to be populated with payload data.
	 * 
	 * @return The length of the payload.
	 * 
	 * @throws IllegalStateException The action is not READ_MESSAGE.
	 * 
	 * @throws ShortBufferException The message buffer does not have
	 * sufficient bytes for a valid message or the payload buffer does
	 * not have enough space for the decrypted payload. 
	 * 
	 * @throws BadPaddingException A MAC value in the message failed
	 * to verify.
	 * 
	 * @see #getAction()
	 * @see #writeMessage(byte[], int, byte[], int, int)
	 */
	public int readMessage(byte[] message, int messageOffset, int messageLength, byte[] payload, int payloadOffset) throws ShortBufferException, BadPaddingException
	{
		boolean success = false;
		int messageEnd = messageOffset + messageLength;
		
		// Validate the parameters.
		if (action != READ_MESSAGE) {
			throw new IllegalStateException
				("Handshake state does not allow reading messages");
		}
		if (messageOffset > message.length || payloadOffset > payload.length) {
			throw new ShortBufferException();
		}
		if (messageLength > (message.length - messageOffset)) {
			throw new ShortBufferException();
		}
		
		// Process the message.
		try {
			// Process tokens until the direction changes or the patten ends.
			for (;;) {
				if (patternIndex >= pattern.length) {
					// The pattern has finished, so the next action is "split".
					action = SPLIT;
					break;
				}
				short token = pattern[patternIndex++];
				if (token == Pattern.FLIP_DIR) {
					// Change directions, so this message is complete and the
					// next action is "write message".
					action = WRITE_MESSAGE;
					break;
				}
				int space = messageEnd - messageOffset;
				int len, macLen;
				switch (token) {
					case Pattern.E:
					{
						// Save the remote ephemeral key and hash it.
						if (remoteEphemeral == null)
							throw new IllegalStateException("Pattern definition error");
						len = remoteEphemeral.getPublicKeyLength();
						if (space < len)
							throw new ShortBufferException();
						symmetric.mixHash(message, messageOffset, len);
						remoteEphemeral.setPublicKey(message, messageOffset);
						if (remoteEphemeral.isNullPublicKey()) {
							// The remote ephemeral key is null, which means that it is
							// not contributing anything to the security of the session
							// and is in fact downgrading the security to "none at all"
							// in some of the message patterns.  Reject all such keys.
							throw new BadPaddingException("Null remote public key");
						}

						// If the protocol is using pre-shared keys, then also mix
						// the remote ephemeral key into the chaining key.
						messageOffset += len;
					}
					break;
	
					case Pattern.S:
					{
						// Decrypt and read the remote static key.
						if (remotePublicKey == null)
							throw new IllegalStateException("Pattern definition error");
						len = remotePublicKey.getPublicKeyLength();
						macLen = symmetric.getMACLength();
						if (space < (len + macLen))
							throw new ShortBufferException();
						byte[] temp = new byte [len];
						try {
							if (symmetric.decryptAndHash(message, messageOffset, temp, 0, len + macLen) != len)
								throw new ShortBufferException();
							remotePublicKey.setPublicKey(temp, 0);
						} finally {
							Noise.destroy(temp);
						}
						messageOffset += len + macLen;
					}
					break;
	
					case Pattern.EE:
					{
						// DH operation with initiator and responder ephemeral keys.
						mixDH(localEphemeral, remoteEphemeral);
					}
					break;
	
					case Pattern.ES:
					{
						// DH operation with initiator ephemeral and responder static keys.
						if (isInitiator)
							mixDH(localEphemeral, remotePublicKey);
						else
							mixDH(localKeyPair, remoteEphemeral);
					}
					break;
	
					case Pattern.SE:
					{
						// DH operation with initiator static and responder ephemeral keys.
						if (isInitiator)
							mixDH(localKeyPair, remoteEphemeral);
						else
							mixDH(localEphemeral, remotePublicKey);
					}
					break;
	
					default:
					{
						// Unknown token code.  Abort.
						throw new IllegalStateException("Unknown handshake token " + Integer.toString(token));
					}
				}
			}
			
			// Decrypt the message payload.
			int payloadLength = symmetric.decryptAndHash(message, messageOffset, payload, payloadOffset, messageEnd - messageOffset);
			success = true;
			return payloadLength;
		} finally {
			// If we failed, then clear any sensitive data that may have
			// already been written to the payload buffer.
			if (!success) {
				Arrays.fill(payload, payloadOffset, payload.length - payloadOffset, (byte)0);
				action = FAILED;
			}
		}
	}

	/**
	 * Splits the transport encryption CipherState objects out of
	 * this HandshakeState object once the handshake completes.
	 * 
	 * @return The pair of ciphers for sending and receiving.
	 * 
	 * @throws IllegalStateException The action is not SPLIT.
	 */
	public CipherStatePair split()
	{
		if (action != SPLIT) {
			throw new IllegalStateException
				("Handshake has not finished");
		}
		CipherStatePair pair = symmetric.split();
		if (!isInitiator)
			pair.swap();
		action = COMPLETE;
		return pair;
	}

	/**
	 * Splits the transport encryption CipherState objects out of
	 * this HandshakeObject after mixing in a secondary symmetric key.
	 * 
	 * @param secondaryKey The buffer containing the secondary key.
	 * @param offset The offset of the first secondary key byte.
	 * @param length The length of the secondary key in bytes, which
	 * must be either 0 or 32.
	 * @return The pair of ciphers for sending and receiving.
	 * 
	 * @throws IllegalStateException The action is not SPLIT.
	 * 
	 * @throws IllegalArgumentException The length is not 0 or 32.
	 */
	public CipherStatePair split(byte[] secondaryKey, int offset, int length)
	{
		if (action != SPLIT) {
			throw new IllegalStateException
				("Handshake has not finished");
		}
		CipherStatePair pair = symmetric.split(secondaryKey, offset, length);
		if (!isInitiator) {
			// Swap the sender and receiver objects for the responder
			// to make it easier on the application to know which is which.
			pair.swap();
		}
		action = COMPLETE;
		return pair;
	}
	
	/**
	 * Gets the current value of the handshake hash.
	 * 
	 * @return The handshake hash.  This must not be modified by the caller.
	 * 
	 * @throws IllegalStateException The action is not SPLIT or COMPLETE.
	 */
	public byte[] getHandshakeHash()
	{
		if (action != SPLIT && action != COMPLETE) {
			throw new IllegalStateException
				("Handshake has not completed");
		}
		return symmetric.getHandshakeHash();
	}

	@Override
	public void destroy() {
		if (symmetric != null)
			symmetric.destroy();
		if (localKeyPair != null)
			localKeyPair.destroy();
		if (localEphemeral != null)
			localEphemeral.destroy();
		if (remotePublicKey != null)
			remotePublicKey.destroy();
		if (remoteEphemeral != null)
			remoteEphemeral.destroy();
		if (prologue != null)
			Noise.destroy(prologue);
	}
	
	/**
	 * Computes the requirements for a handshake.
	 * 
	 * @param flags The flags from the handshake's pattern.
	 * @param prefix The prefix from the protocol name; typically
	 * "Noise" or "NoisePSK".
	 * @param role The role, HandshakeState.INITIATOR or HandshakeState.RESPONDER.
	 * @param isFallback Set to true if we need the requirements for a
	 * fallback pattern; false for a regular pattern.
	 * 
	 * @return The set of requirements for the handshake.
	 */
	private static int computeRequirements(short flags, String prefix, int role, boolean isFallback)
	{
	    int requirements = 0;
	    if ((flags & Pattern.FLAG_LOCAL_STATIC) != 0) {
	        requirements |= LOCAL_REQUIRED;
	    }
	    if ((flags & Pattern.FLAG_LOCAL_REQUIRED) != 0) {
	        requirements |= LOCAL_REQUIRED;
	        requirements |= LOCAL_PREMSG;
	    }
	    if ((flags & Pattern.FLAG_REMOTE_REQUIRED) != 0) {
	        requirements |= REMOTE_REQUIRED;
	        requirements |= REMOTE_PREMSG;
	    }
	    if ((flags & (Pattern.FLAG_REMOTE_EPHEM_REQ |
	    		      Pattern.FLAG_LOCAL_EPHEM_REQ)) != 0) {
	        if (isFallback)
	            requirements |= FALLBACK_PREMSG;
	    }
	    return requirements;
	}

	/**
	 *  I2P for mixing in padding in messages 1 and 2
	 */
	public void mixHash(byte[] data, int offset, int length) {
		symmetric.mixHash(data, offset, length);
	}

	/**
	 *  I2P for getting chaining key for siphash calc
	 *  @return a copy
	 */
	public byte[] getChainingKey() {
		return symmetric.getChainingKey();
	}

	/**
	 *  I2P debug
	 */
	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder();
		buf.append("Handshake State:\n");
		buf.append(symmetric.toString());

		byte[] tmp = new byte[32];

		DHState dh = localKeyPair;
		buf.append("Local static public key (s) :      ");
		if (dh != null && dh.hasPublicKey()) {
			dh.getPublicKey(tmp, 0);
			buf.append(net.i2p.data.Base64.encode(tmp));
		} else {
			buf.append("null");
		}
		buf.append('\n');

		dh = remotePublicKey;
		buf.append("Remote static public key (rs) :    ");
		if (dh != null && dh.hasPublicKey()) {
			dh.getPublicKey(tmp, 0);
			buf.append(net.i2p.data.Base64.encode(tmp));
		} else {
			buf.append("null");
		}
		buf.append('\n');

		dh = localEphemeral;
		buf.append("Local ephemeral public key (e) :   ");
		if (dh != null && dh.hasPublicKey()) {
			dh.getPublicKey(tmp, 0);
			buf.append(net.i2p.data.Base64.encode(tmp));
		} else {
			buf.append("null");
		}
		buf.append('\n');

		dh = remoteEphemeral;
		buf.append("Remote ephemeral public key (re) : ");
		if (dh != null && dh.hasPublicKey()) {
			dh.getPublicKey(tmp, 0);
			buf.append(net.i2p.data.Base64.encode(tmp));
		} else {
			buf.append("null");
		}
		buf.append('\n');

		return buf.toString();
	}
}
