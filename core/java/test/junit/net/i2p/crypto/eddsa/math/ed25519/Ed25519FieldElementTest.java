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
package net.i2p.crypto.eddsa.math.ed25519;

import net.i2p.crypto.eddsa.math.*;
import org.hamcrest.core.*;
import org.junit.*;
import static org.hamcrest.MatcherAssert.assertThat;


import java.math.BigInteger;

/**
 * Tests rely on the BigInteger class.
 */
public class Ed25519FieldElementTest extends AbstractFieldElementTest {

    protected FieldElement getRandomFieldElement() {
        return MathUtils.getRandomFieldElement();
    }

    protected BigInteger toBigInteger(FieldElement f) {
        return MathUtils.toBigInteger(f);
    }

    protected BigInteger getQ() {
        return MathUtils.getQ();
    }

    protected Field getField() {
        return MathUtils.getField();
    }

    // region constructor

    @Test
    public void canConstructFieldElementFromArrayWithCorrectLength() {
        // Assert:
        new Ed25519FieldElement(MathUtils.getField(), new int[10]);
    }

    @Test (expected = IllegalArgumentException.class)
    public void cannotConstructFieldElementFromArrayWithIncorrectLength() {
        // Assert:
        new Ed25519FieldElement(MathUtils.getField(), new int[9]);
    }

    @Test (expected = IllegalArgumentException.class)
    public void cannotConstructFieldElementWithoutField() {
        // Assert:
        new Ed25519FieldElement(null, new int[9]);
    }

    // endregion

    // region isNonZero

    protected FieldElement getZeroFieldElement() {
        return new Ed25519FieldElement(MathUtils.getField(), new int[10]);
    }

    protected FieldElement getNonZeroFieldElement() {
        final int[] t = new int[10];
        t[0] = 5;
        return new Ed25519FieldElement(MathUtils.getField(), t);
    }

    // endregion

    // region toString

    @Test
    public void toStringReturnsCorrectRepresentation() {
        // Arrange:
        final byte[] bytes = new byte[32];
        for (int i=0; i<32; i++) {
            bytes[i] = (byte)(i+1);
        }
        final FieldElement f = MathUtils.getField().getEncoding().decode(bytes);

        // Act:
        final String fAsString = f.toString();
        final StringBuilder builder = new StringBuilder();
        builder.append("[Ed25519FieldElement val=");
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        builder.append("]");

        // Assert:
        assertThat(fAsString, IsEqual.equalTo(builder.toString()));
    }

    // endregion
}
