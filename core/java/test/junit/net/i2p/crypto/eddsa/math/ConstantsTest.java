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
package net.i2p.crypto.eddsa.math;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;
import static org.hamcrest.MatcherAssert.assertThat;


import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;

import org.junit.Test;

/**
 * Based on the tests in checkparams.py from the Python Ed25519 implementation.
 * @author str4d
 *
 */
public class ConstantsTest {
    static final EdDSANamedCurveSpec ed25519 = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
    static final Curve curve = ed25519.getCurve();

    static final FieldElement ZERO = curve.getField().ZERO;
    static final FieldElement ONE = curve.getField().ONE;
    static final FieldElement TWO = curve.getField().TWO;

    static final GroupElement P3_ZERO = GroupElement.p3(curve, ZERO, ONE, ONE, ZERO);

    @Test
    public void testb() {
        int b = curve.getField().getb();
        assertThat(b, is(greaterThanOrEqualTo(10)));
        try {
            MessageDigest h = MessageDigest.getInstance(ed25519.getHashAlgorithm());
            assertThat(8 * h.getDigestLength(), is(equalTo(2 * b)));
        } catch (NoSuchAlgorithmException e) {
            fail(e.getMessage());
        }
    }

    /*@Test
    public void testq() {
        FieldElement q = curve.getField().getQ();
        assertThat(TWO.modPow(q.subtractOne(), q), is(equalTo(ONE)));
        assertThat(q.mod(curve.getField().FOUR), is(equalTo(ONE)));
    }

    @Test
    public void testl() {
        int b = curve.getField().getb();
        BigInteger l = ed25519.getL();
        assertThat(TWO.modPow(l.subtract(BigInteger.ONE), l), is(equalTo(ONE)));
        assertThat(l, is(greaterThanOrEqualTo(BigInteger.valueOf(2).pow(b-4))));
        assertThat(l, is(lessThanOrEqualTo(BigInteger.valueOf(2).pow(b-3))));
    }

    @Test
    public void testd() {
        FieldElement q = curve.getField().getQ();
        FieldElement qm1 = q.subtractOne();
        assertThat(curve.getD().modPow(qm1.divide(curve.getField().TWO), q), is(equalTo(qm1)));
    }

    @Test
    public void testI() {
        FieldElement q = curve.getField().getQ();
        assertThat(curve.getI().modPow(curve.getField().TWO, q), is(equalTo(q.subtractOne())));
    }*/

    @Test
    public void testB() {
        GroupElement B = ed25519.getB();
        assertThat(B.isOnCurve(curve), is(true));
        //assertThat(B.scalarMultiply(new BigIntegerLittleEndianEncoding().encode(ed25519.getL(), curve.getField().getb()/8)), is(equalTo(P3_ZERO)));
    }
}
