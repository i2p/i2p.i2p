package net.i2p.crypto.eddsa.math.ed25519;

import net.i2p.crypto.eddsa.math.*;
import org.hamcrest.core.*;
import org.junit.*;

import java.math.BigInteger;

/**
 * Tests rely on the BigInteger class.
 */
public class Ed25519FieldElementTest {

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

    @Test
    public void isNonZeroReturnsFalseIfFieldElementIsZero() {
        // Act:
        final FieldElement f = new Ed25519FieldElement(MathUtils.getField(), new int[10]);

        // Assert:
        Assert.assertThat(f.isNonZero(), IsEqual.equalTo(false));
    }

    @Test
    public void isNonZeroReturnsTrueIfFieldElementIsNonZero() {
        // Act:
        final int[] t = new int[10];
        t[0] = 5;
        final FieldElement f = new Ed25519FieldElement(MathUtils.getField(), t);

        // Assert:
        Assert.assertThat(f.isNonZero(), IsEqual.equalTo(true));
    }

    // endregion

    // region mod q arithmetic

    @Test
    public void addReturnsCorrectResult() {
        for (int i=0; i<1000; i++) {
            // Arrange:
            final FieldElement f1 = MathUtils.getRandomFieldElement();
            final FieldElement f2 = MathUtils.getRandomFieldElement();
            final BigInteger b1 = MathUtils.toBigInteger(f1);
            final BigInteger b2 = MathUtils.toBigInteger(f2);

            // Act:
            final FieldElement f3 = f1.add(f2);
            final BigInteger b3 = MathUtils.toBigInteger(f3).mod(MathUtils.getQ());

            // Assert:
            Assert.assertThat(b3, IsEqual.equalTo(b1.add(b2).mod(MathUtils.getQ())));
        }
    }

    @Test
    public void subtractReturnsCorrectResult() {
        for (int i=0; i<1000; i++) {
            // Arrange:
            final FieldElement f1 = MathUtils.getRandomFieldElement();
            final FieldElement f2 = MathUtils.getRandomFieldElement();
            final BigInteger b1 = MathUtils.toBigInteger(f1);
            final BigInteger b2 = MathUtils.toBigInteger(f2);

            // Act:
            final FieldElement f3 = f1.subtract(f2);
            final BigInteger b3 = MathUtils.toBigInteger(f3).mod(MathUtils.getQ());

            // Assert:
            Assert.assertThat(b3, IsEqual.equalTo(b1.subtract(b2).mod(MathUtils.getQ())));
        }
    }

    @Test
    public void negateReturnsCorrectResult() {
        for (int i=0; i<1000; i++) {
            // Arrange:
            final FieldElement f1 = MathUtils.getRandomFieldElement();
            final BigInteger b1 = MathUtils.toBigInteger(f1);

            // Act:
            final FieldElement f2 = f1.negate();
            final BigInteger b2 = MathUtils.toBigInteger(f2).mod(MathUtils.getQ());

            // Assert:
            Assert.assertThat(b2, IsEqual.equalTo(b1.negate().mod(MathUtils.getQ())));
        }
    }

    @Test
    public void multiplyReturnsCorrectResult() {
        for (int i=0; i<1000; i++) {
            // Arrange:
            final FieldElement f1 = MathUtils.getRandomFieldElement();
            final FieldElement f2 = MathUtils.getRandomFieldElement();
            final BigInteger b1 = MathUtils.toBigInteger(f1);
            final BigInteger b2 = MathUtils.toBigInteger(f2);

            // Act:
            final FieldElement f3 = f1.multiply(f2);
            final BigInteger b3 = MathUtils.toBigInteger(f3).mod(MathUtils.getQ());

            // Assert:
            Assert.assertThat(b3, IsEqual.equalTo(b1.multiply(b2).mod(MathUtils.getQ())));
        }
    }

    @Test
    public void squareReturnsCorrectResult() {
        for (int i=0; i<1000; i++) {
            // Arrange:
            final FieldElement f1 = MathUtils.getRandomFieldElement();
            final BigInteger b1 = MathUtils.toBigInteger(f1);

            // Act:
            final FieldElement f2 = f1.square();
            final BigInteger b2 = MathUtils.toBigInteger(f2).mod(MathUtils.getQ());

            // Assert:
            Assert.assertThat(b2, IsEqual.equalTo(b1.multiply(b1).mod(MathUtils.getQ())));
        }
    }

    @Test
    public void squareAndDoubleReturnsCorrectResult() {
        for (int i=0; i<1000; i++) {
            // Arrange:
            final FieldElement f1 = MathUtils.getRandomFieldElement();
            final BigInteger b1 = MathUtils.toBigInteger(f1);

            // Act:
            final FieldElement f2 = f1.squareAndDouble();
            final BigInteger b2 = MathUtils.toBigInteger(f2).mod(MathUtils.getQ());

            // Assert:
            Assert.assertThat(b2, IsEqual.equalTo(b1.multiply(b1).multiply(new BigInteger("2")).mod(MathUtils.getQ())));
        }
    }

    @Test
    public void invertReturnsCorrectResult() {
        for (int i=0; i<1000; i++) {
            // Arrange:
            final FieldElement f1 = MathUtils.getRandomFieldElement();
            final BigInteger b1 = MathUtils.toBigInteger(f1);

            // Act:
            final FieldElement f2 = f1.invert();
            final BigInteger b2 = MathUtils.toBigInteger(f2).mod(MathUtils.getQ());

            // Assert:
            Assert.assertThat(b2, IsEqual.equalTo(b1.modInverse(MathUtils.getQ())));
        }
    }

    @Test
    public void pow22523ReturnsCorrectResult() {
        for (int i=0; i<1000; i++) {
            // Arrange:
            final FieldElement f1 = MathUtils.getRandomFieldElement();
            final BigInteger b1 = MathUtils.toBigInteger(f1);

            // Act:
            final FieldElement f2 = f1.pow22523();
            final BigInteger b2 = MathUtils.toBigInteger(f2).mod(MathUtils.getQ());

            // Assert:
            Assert.assertThat(b2, IsEqual.equalTo(b1.modPow(BigInteger.ONE.shiftLeft(252).subtract(new BigInteger("3")), MathUtils.getQ())));
        }
    }

    // endregion

    // region hashCode / equals

    @Test
    public void equalsOnlyReturnsTrueForEquivalentObjects() {
        // Arrange:
        final FieldElement f1 = MathUtils.getRandomFieldElement();
        final FieldElement f2 = MathUtils.getField().getEncoding().decode(f1.toByteArray());
        final FieldElement f3 = MathUtils.getRandomFieldElement();
        final FieldElement f4 = MathUtils.getRandomFieldElement();

        // Assert:
        Assert.assertThat(f1, IsEqual.equalTo(f2));
        Assert.assertThat(f1, IsNot.not(IsEqual.equalTo(f3)));
        Assert.assertThat(f1, IsNot.not(IsEqual.equalTo(f4)));
        Assert.assertThat(f3, IsNot.not(IsEqual.equalTo(f4)));
    }

    @Test
    public void hashCodesAreEqualForEquivalentObjects() {
        // Arrange:
        final FieldElement f1 = MathUtils.getRandomFieldElement();
        final FieldElement f2 = MathUtils.getField().getEncoding().decode(f1.toByteArray());
        final FieldElement f3 = MathUtils.getRandomFieldElement();
        final FieldElement f4 = MathUtils.getRandomFieldElement();

        // Assert:
        Assert.assertThat(f1.hashCode(), IsEqual.equalTo(f2.hashCode()));
        Assert.assertThat(f1.hashCode(), IsNot.not(IsEqual.equalTo(f3.hashCode())));
        Assert.assertThat(f1.hashCode(), IsNot.not(IsEqual.equalTo(f4.hashCode())));
        Assert.assertThat(f3.hashCode(), IsNot.not(IsEqual.equalTo(f4.hashCode())));
    }

    // endregion

    //region toString

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
        Assert.assertThat(fAsString, IsEqual.equalTo(builder.toString()));
    }

    // endregion
}
