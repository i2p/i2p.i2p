package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * No warranty of any kind, either expressed or implied.  
 */

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PublicKey;

/**
 *  A backend for storing and retrieving SigningPublicKeys
 *  to be used for verifying signatures.
 *
 *  @since 0.9.9
 */
public interface KeyRing {

    /**
     *  Get a key.
     *  Throws on all errors.
     *  @param scope a domain identifier, indicating router update, reseed, etc.
     *  @return null if none
     */
    public PublicKey getKey(String keyName, String scope, SigType type)
                            throws GeneralSecurityException, IOException;

    /**
     *  Store a key.
     *  Throws on all errors.
     *  @param scope a domain identifier, indicating router update, reseed, etc.
     */
    public void setKey(String keyName, String scope, PublicKey key)
                            throws GeneralSecurityException, IOException;
}
