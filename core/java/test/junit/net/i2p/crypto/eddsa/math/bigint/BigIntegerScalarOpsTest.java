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
package net.i2p.crypto.eddsa.math.bigint;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;


import java.math.BigInteger;

import net.i2p.crypto.eddsa.Utils;
import net.i2p.crypto.eddsa.math.Field;
import net.i2p.crypto.eddsa.math.ScalarOps;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;

import org.junit.Test;

/**
 * @author str4d
 *
 */
public class BigIntegerScalarOpsTest {

    static final EdDSANamedCurveSpec ed25519 = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
    static final Field ed25519Field = ed25519.getCurve().getField();

    /**
     * Test method for {@link net.i2p.crypto.eddsa.math.bigint.BigIntegerScalarOps#reduce(byte[])}.
     */
    @Test
    public void testReduce() {
        ScalarOps sc = new BigIntegerScalarOps(ed25519Field,
                new BigInteger("5"));
        assertThat(sc.reduce(new byte[] {7}),
                is(equalTo(Utils.hexToBytes("0200000000000000000000000000000000000000000000000000000000000000"))));

        ScalarOps sc2 = new BigIntegerScalarOps(ed25519Field,
                new BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989"));
        // Example from test case 1
        byte[] r = Utils.hexToBytes("b6b19cd8e0426f5983fa112d89a143aa97dab8bc5deb8d5b6253c928b65272f4044098c2a990039cde5b6a4818df0bfb6e40dc5dee54248032962323e701352d");
        assertThat(sc2.reduce(r), is(equalTo(Utils.hexToBytes("f38907308c893deaf244787db4af53682249107418afc2edc58f75ac58a07404"))));
    }

    /**
     * Test method for {@link net.i2p.crypto.eddsa.math.bigint.BigIntegerScalarOps#multiplyAndAdd(byte[], byte[], byte[])}.
     */
    @Test
    public void testMultiplyAndAdd() {
        ScalarOps sc = new BigIntegerScalarOps(ed25519Field,
                new BigInteger("5"));
        assertThat(sc.multiplyAndAdd(new byte[] {7}, new byte[] {2}, new byte[] {5}),
                is(equalTo(Utils.hexToBytes("0400000000000000000000000000000000000000000000000000000000000000"))));

        ScalarOps sc2 = new BigIntegerScalarOps(ed25519Field,
                new BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989"));
        // Example from test case 1
        byte[] h = Utils.hexToBytes("86eabc8e4c96193d290504e7c600df6cf8d8256131ec2c138a3e7e162e525404");
        byte[] a = Utils.hexToBytes("307c83864f2833cb427a2ef1c00a013cfdff2768d980c0a3a520f006904de94f");
        byte[] r = Utils.hexToBytes("f38907308c893deaf244787db4af53682249107418afc2edc58f75ac58a07404");
        byte[] S = Utils.hexToBytes("5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
        assertThat(sc2.multiplyAndAdd(h, a, r), is(equalTo(S)));
    }

}
