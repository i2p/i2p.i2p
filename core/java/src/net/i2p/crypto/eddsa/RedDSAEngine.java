/**
 * EdDSA-Java by str4d
 *
 * To the extent possible under law, the person who associated CC0 with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java.
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <https://creativecommons.org/publicdomain/zero/1.0/>.
 *
 */
package net.i2p.crypto.eddsa;

import java.security.MessageDigest;

import net.i2p.util.RandomSource;

/**
 * Signing and verification for REdDSA using SHA-512 and the Ed25519 curve.
 * Ref: Zcash Protocol Specification, Version 2018.0-beta-33 [Overwinter+Sapling]
 * Sections 4.1.6.1, 4.1.6.2, 5.4.6
 *
 *<p>
 * The EdDSA sign and verify algorithms do not interact well with
 * the Java Signature API, as one or more update() methods must be
 * called before sign() or verify(). Using the standard API,
 * this implementation must copy and buffer all data passed in
 * via update().
 *</p><p>
 * This implementation offers two ways to avoid this copying,
 * but only if all data to be signed or verified is available
 * in a single byte array.
 *</p><p>
 *Option 1:
 *</p><ol>
 *<li>Call initSign() or initVerify() as usual.
 *</li><li>Call setParameter(ONE_SHOT_MODE)
 *</li><li>Call update(byte[]) or update(byte[], int, int) exactly once
 *</li><li>Call sign() or verify() as usual.
 *</li><li>If doing additional one-shot signs or verifies with this object, you must
 *         call setParameter(ONE_SHOT_MODE) each time
 *</li></ol>
 *
 *<p>
 *Option 2:
 *</p><ol>
 *<li>Call initSign() or initVerify() as usual.
 *</li><li>Call one of the signOneShot() or verifyOneShot() methods.
 *</li><li>If doing additional one-shot signs or verifies with this object,
 *         just call signOneShot() or verifyOneShot() again.
 *</li></ol>
 *
 * @since 0.9.39
 */
public final class RedDSAEngine extends EdDSAEngine {

    /**
     * No specific EdDSA-internal hash requested, allows any EdDSA key.
     */
    public RedDSAEngine() {
        super();
    }

    /**
     * Specific EdDSA-internal hash requested, only matching keys will be allowed.
     * @param digest the hash algorithm that keys must have to sign or verify.
     */
    public RedDSAEngine(MessageDigest digest) {
        super(digest);
    }

    @Override
    protected void digestInitSign(EdDSAPrivateKey privKey) {
        // Preparing for hash
        // r = H(T, pubkey, M)
        byte[] t = new byte[digest.getDigestLength() + 16];
        RandomSource.getInstance().nextBytes(t);
        digest.update(t);
        digest.update(privKey.getAbyte());
    }
}
