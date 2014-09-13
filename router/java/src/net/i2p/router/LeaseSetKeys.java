package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;

/**
 * Wrap up the keys given to the router when a destination connects to it.
 * Used only by KeyManager.
 */
public class LeaseSetKeys {
    private final SigningPrivateKey _revocationKey;
    private final PrivateKey _decryptionKey;

    /**
     *  @param dest unused
     *  @param revocationKey unused
     *  @param decryptionKey non-null
     */
    public LeaseSetKeys(Destination dest, SigningPrivateKey revocationKey, PrivateKey decryptionKey) {
	_revocationKey = revocationKey;
	_decryptionKey = decryptionKey;
    }

    /**
     * Key with which a LeaseSet can be revoked (by republishing it with no Leases)
     *
     * Deprecated, unused
     */
    public SigningPrivateKey getRevocationKey() { return _revocationKey; }

    /**
     * Decryption key which can open up garlic messages encrypted to the 
     * LeaseSet's public key.  This is used because the general public does not
     * know on what router the destination is connected and as such can't encrypt 
     * to that router's normal public key.
     *
     */
    public PrivateKey getDecryptionKey() { return _decryptionKey; }

}
