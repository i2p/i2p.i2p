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

import java.math.BigInteger;

import net.i2p.crypto.eddsa.math.Field;
import net.i2p.crypto.eddsa.math.GroupElement;
import net.i2p.crypto.eddsa.math.ScalarOps;
import net.i2p.crypto.eddsa.math.bigint.BigIntegerLittleEndianEncoding;
import net.i2p.crypto.eddsa.math.bigint.BigIntegerScalarOps;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

/**
 * Utilities for Blinding EdDSA keys.
 * PRELIMINARY - Subject to change - see proposal 123
 *
 * @since 0.9.38
 */
public final class EdDSABlinding {

    private static final byte[] ONE = Utils.hexToBytes("0100000000000000000000000000000000000000000000000000000000000000");
    private static final Field FIELD = EdDSANamedCurveTable.getByName("Ed25519").getCurve().getField();
    public static final BigInteger ORDER = new BigInteger("2").pow(252).add(new BigInteger("27742317777372353535851937790883648493"));

    private EdDSABlinding() {}

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519
     *  @param alpha generated from hash of secret data
     *  @throws UnsupportedOperationException unless supported
     */
    public static EdDSAPublicKey blind(EdDSAPublicKey key, EdDSAPrivateKey alpha) {
        GroupElement a = key.getA();
        GroupElement aa = alpha.getA();
        // Add the two public keys together.
        GroupElement d = a.add(aa.toCached());
        EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(d, key.getParams());
        EdDSAPublicKey rv = new EdDSAPublicKey(pubKey);
        //System.out.println("Adding pubkey\n" +
        //   net.i2p.util.HexDump.dump(key.getAbyte()) +
        //   "\nplus privkey\n" +
        //   net.i2p.util.HexDump.dump(alpha.geta()) +
        //   "\nequals\n" +
        //   net.i2p.util.HexDump.dump(rv.getAbyte()));
        return rv;
    }

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519
     *  @param alpha generated from hash of secret data
     *  @throws UnsupportedOperationException unless supported
     */
    public static EdDSAPrivateKey blind(EdDSAPrivateKey key, EdDSAPrivateKey alpha) {
        byte[] a = key.geta();
        byte[] aa = alpha.geta();
        Field f = key.getParams().getCurve().getField();
        BigIntegerLittleEndianEncoding enc = new BigIntegerLittleEndianEncoding();
        enc.setField(f);
        ScalarOps sc = new BigIntegerScalarOps(f, ORDER);
        // Add the two private keys together.
        // just for now, since we don't have a pure add.
        byte[] d = sc.multiplyAndAdd(ONE, a, aa);
        //System.out.println("Adding privkeys\n" +
        //   net.i2p.util.HexDump.dump(a) +
        //   "\nplus\n" +
        //   net.i2p.util.HexDump.dump(aa) +
        //   "\nequals\n" +
        //   net.i2p.util.HexDump.dump(d));
        EdDSAPrivateKeySpec privKey = new EdDSAPrivateKeySpec(d, null, key.getParams());
        return new EdDSAPrivateKey(privKey);
    }

    /**
     *  Unimplemented, probably not needed except for testing.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519
     *  @param alpha generated from hash of secret data
     *  @throws UnsupportedOperationException unless supported
     */
    public static EdDSAPrivateKey unblind(EdDSAPrivateKey key, EdDSAPrivateKey alpha) {
        throw new UnsupportedOperationException();
    }

    /**
     *  Use to generate alpha
     *
     *  @param b 64 bytes little endian of random
     *  @return 32 bytes little endian mod l
     */
    public static byte[] reduce(byte[] b) {
        if (b.length != 64)
            throw new IllegalArgumentException("Must be 64 bytes");
        ScalarOps sc = new BigIntegerScalarOps(FIELD, ORDER);
        return sc.reduce(b);
    }
}
