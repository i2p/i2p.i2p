package net.i2p.crypto.x25519;

import com.southernstorm.noise.crypto.x25519.Curve25519;

import net.i2p.crypto.EncType;
import net.i2p.data.DataHelper;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;

/**
 * DH wrapper around Noise's Curve25519 with I2P types.
 *
 * @since 0.9.41
 */
public class X25519DH {

    private static final EncType TYPE = EncType.ECIES_X25519;

    private X25519DH() {}

    /**
     * DH
     *
     * @param pub MUST have MSB high bit cleared, i.e. pub.getData()[31] &amp; 0x80 == 0
     * @return ECIES_X25519
     * @throws IllegalArgumentException if not ECIES_X25519
     */
    public static SessionKey dh(PrivateKey priv, PublicKey pub) {
        if (priv.getType() != TYPE || pub.getType() != TYPE)
            throw new IllegalArgumentException();
        byte[] rv = new byte[32];
        Curve25519.eval(rv, 0, priv.getData(), pub.getData());
        return new SessionKey(rv);
    }

    /**
     * Test vectors from RFC 7748
     *
     * @since 0.9.62
     */
/****
    public static void main(String[] args) {
        // k = scalar = private
        // u = coordinate = public
        byte[] apriv = DataHelper.fromHexString("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4");
        if (apriv.length == 33) apriv = java.util.Arrays.copyOfRange(apriv, 1, 33);
        PrivateKey apr = new PrivateKey(TYPE, apriv);
        byte[] bpub = DataHelper.fromHexString("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c");
        if (bpub.length == 33) bpub = java.util.Arrays.copyOfRange(bpub, 1, 33);
        PublicKey bpu = new PublicKey(TYPE, bpub);
        SessionKey sk = dh(apr, bpu);
        // Output u-coordinate:
        // c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552
        System.out.println(DataHelper.toHexString(sk.getData()));

        apriv = DataHelper.fromHexString("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d");
        if (apriv.length == 33) apriv = java.util.Arrays.copyOfRange(apriv, 1, 33);
        apr = new PrivateKey(TYPE, apriv);
        // this vector has high bit set
        bpub = DataHelper.fromHexString("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493");
        if (bpub.length == 33) bpub = java.util.Arrays.copyOfRange(bpub, 1, 33);
        // When receiving such an array, implementations of X25519
        // (but not X448) MUST mask the most significant bit in the final byte.
        // This is done to preserve compatibility with point formats that
        // reserve the sign bit for use in other protocols and to increase
        // resistance to implementation fingerprinting.
        bpub[31] &= 0x7f;
        bpu = new PublicKey(TYPE, bpub);
        sk = dh(apr, bpu);
        // Output u-coordinate:
        // 95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957
        System.out.println(DataHelper.toHexString(sk.getData()));

        apriv = new byte[32];
        apriv[0] = 0x09;
        apr = new PrivateKey(EncType.ECIES_X25519, apriv);
        bpub = new byte[32];
        bpub[0] = 0x09;
        bpu = new PublicKey(EncType.ECIES_X25519, bpub);
        sk = dh(apr, bpu);
        // After one iteration:
        // 422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079
        // After 1,000,000 iterations:
        // 7c3911e0ab2586fd864497297e575e6f3bc601c0883c30df5f4dd2d24f665424
        System.out.println(DataHelper.toHexString(sk.getData()));

        // For each iteration, set k to be the result of calling the function
        // and u to be the old value of k.  The final result is the value left
        // in k.
        for (int i = 1; i < 1000; i++) {
            apr.getData()[31] &= 0x7f;
            bpu = new PublicKey(EncType.ECIES_X25519, apr.getData());
            apr = new PrivateKey(EncType.ECIES_X25519, sk.getData());
            sk = dh(apr, bpu);
        }
        // After 1,000 iterations:
        // 684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51
        System.out.println(DataHelper.toHexString(sk.getData()));

        // After 1,000,000 iterations:
        // 7c3911e0ab2586fd864497297e575e6f3bc601c0883c30df5f4dd2d24f665424
    }
****/
}
