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

import java.security.KeyPair;

import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import net.i2p.util.RandomSource;

/**
 *  Default keysize is 256 (Ed25519)
 *
 *  @since 0.9.39
 */
public final class RedKeyPairGenerator extends KeyPairGenerator {

    @Override
    public KeyPair generateKeyPair() {
        if (!initialized)
            initialize(DEFAULT_KEYSIZE, RandomSource.getInstance());

        // 64 bytes
        byte[] seed = new byte[edParams.getCurve().getField().getb()/4];
        random.nextBytes(seed);
        byte[] b = EdDSABlinding.reduce(seed);

        EdDSAPrivateKeySpec privKey = new EdDSAPrivateKeySpec(b, null, edParams);
        EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(privKey.getA(), edParams);

        return new KeyPair(new EdDSAPublicKey(pubKey), new EdDSAPrivateKey(privKey));
    }

/****
    public static void main(String[] args) {
        (new RedKeyPairGenerator()).test();
    }

    public void test() {
        if (!initialized)
            initialize(DEFAULT_KEYSIZE, RandomSource.getInstance());

        // 64 bytes
        byte[] seed = new byte[edParams.getCurve().getField().getb()/4];
        random.nextBytes(seed);
        byte[] b = EdDSABlinding.reduce(seed);
        b[0] &= 0xf0;

        for (int i = 0; i < 16; i++) {
            EdDSAPrivateKeySpec privKey = new EdDSAPrivateKeySpec(b, null, edParams);
            EdDSAPrivateKey pk = new EdDSAPrivateKey(privKey);
            System.out.println("Privkey:\n" + net.i2p.util.HexDump.dump(b));
            System.out.println("Pubkey:\n" + net.i2p.util.HexDump.dump(pk.getAbyte()));
            b[0]++;
        }
    }
****/
}
