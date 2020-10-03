package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.i2p.crypto.EncType;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;

/**
 * Wrap up the keys given to the router when a destination connects to it.
 * Used by KeyManager, ClientMessageEventListener, GarlicMessageReceiver.
 */
public class LeaseSetKeys {
    private final SigningPrivateKey _revocationKey;
    private final PrivateKey _decryptionKey;
    private final PrivateKey _decryptionKeyEC;

    /**
     * Unmodifiable, ElGamal only
     * @since 0.9.44
     */
    public static final Set<EncType> SET_ELG = Collections.unmodifiableSet(EnumSet.of(EncType.ELGAMAL_2048));
    /**
     * Unmodifiable, ECIES-X25519 only
     * @since public since 0.9.46
     */
    public static final Set<EncType> SET_EC = Collections.unmodifiableSet(EnumSet.of(EncType.ECIES_X25519));
    /**
     * Unmodifiable, ElGamal and ECIES-X25519.
     * @since public since 0.9.48
     */
    public static final Set<EncType> SET_BOTH = Collections.unmodifiableSet(EnumSet.of(EncType.ELGAMAL_2048, EncType.ECIES_X25519));
    private static final Set<EncType> SET_NONE = Collections.emptySet();

    /**
     *  Client with a single key
     *
     *  @param dest unused
     *  @param revocationKey unused, may be null
     *  @param decryptionKey non-null
     */
    public LeaseSetKeys(Destination dest, SigningPrivateKey revocationKey, PrivateKey decryptionKey) {
        _revocationKey = revocationKey;
        EncType type = decryptionKey.getType();
        if (type == EncType.ELGAMAL_2048) {
            _decryptionKey = decryptionKey;
            _decryptionKeyEC = null;
        } else if (type == EncType.ECIES_X25519) {
            _decryptionKey = null;
            _decryptionKeyEC = decryptionKey;
        } else {
            throw new IllegalArgumentException("Unknown type " + type);
        }
    }

    /**
     *  Client with multiple keys
     *
     *  @param dest unused
     *  @param revocationKey unused, may be null
     *  @param decryptionKeys non-null, non-empty
     *  @since 0.9.44
     */
    public LeaseSetKeys(Destination dest, SigningPrivateKey revocationKey, List<PrivateKey> decryptionKeys) {
        if (decryptionKeys.isEmpty())
            throw new IllegalArgumentException("no keys");
        _revocationKey = revocationKey;
        PrivateKey elg = null;
        PrivateKey ec = null;
        for (PrivateKey pk : decryptionKeys) {
            EncType type = pk.getType();
            if (type == EncType.ELGAMAL_2048) {
                if (elg != null)
                    throw new IllegalArgumentException("Multiple keys same type");
                elg = pk;
            } else if (type == EncType.ECIES_X25519) {
                if (ec != null)
                    throw new IllegalArgumentException("Multiple keys same type");
                ec = pk;
            } else {
                throw new IllegalArgumentException("Unknown type " + type);
            }
        }
        _decryptionKey = elg;
        _decryptionKeyEC = ec;
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
     * @return ElGamal key or null if the LS does not support ElGamal
     */
    public PrivateKey getDecryptionKey() { return _decryptionKey; }

    /**
     * Decryption key which can open up garlic messages encrypted to the 
     * LeaseSet's public key.  This is used because the general public does not
     * know on what router the destination is connected and as such can't encrypt 
     * to that router's normal public key.
     *
     * @return key of the specified type or null if the LS does not support that type
     * @since 0.9.44
     */
    public PrivateKey getDecryptionKey(EncType type) {
        if (type == EncType.ELGAMAL_2048)
            return _decryptionKey;
        if (type == EncType.ECIES_X25519)
            return _decryptionKeyEC;
        return null;
    }

    /**
     * Do we support this type of encryption?
     *
     * @since 0.9.44
     */
    public boolean isSupported(EncType type) {
        if (type == EncType.ELGAMAL_2048)
            return _decryptionKey != null;
        if (type == EncType.ECIES_X25519)
            return _decryptionKeyEC != null;
        return false;
    }

    /**
     *  What types of encryption are supported?
     *
     *  @since 0.9.44
     */
    public Set<EncType> getSupportedEncryption() {
        if (_decryptionKey != null)
            return (_decryptionKeyEC != null) ? SET_BOTH : SET_ELG;
        return (_decryptionKeyEC != null) ? SET_EC : SET_NONE;
    }
}
