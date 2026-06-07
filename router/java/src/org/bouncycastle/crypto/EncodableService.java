package org.bouncycastle.crypto;

/**
 *  Encodable services allow you to download an encoded copy of their internal state. This is useful for the situation where
 *  you need to generate a signature on an external device and it allows for "sign with last round", so a copy of the
 *  internal state of the digest, plus the last few blocks of the message are all that needs to be sent, rather than the
 *  entire message.
 */
public interface EncodableService
{
    /**
     * Return an encoded byte array for the services's internal state
     *
     * @return an encoding of the services internal state.
     */
    byte[] getEncodedState();
}
