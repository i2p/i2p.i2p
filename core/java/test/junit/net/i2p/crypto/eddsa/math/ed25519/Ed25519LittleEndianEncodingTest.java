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
import org.hamcrest.core.IsEqual;
import org.junit.*;
import static org.hamcrest.MatcherAssert.assertThat;


import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Tests rely on the BigInteger class.
 */
public class Ed25519LittleEndianEncodingTest {

    private static final SecureRandom random = new SecureRandom();

    @Test
    public void encodeReturnsCorrectByteArrayForSimpleFieldElements() {
        // Arrange:
        final int[] t1 = new int[10];
        final int[] t2 = new int[10];
        t2[0] = 1;
        final FieldElement fieldElement1 = new Ed25519FieldElement(MathUtils.getField(), t1);
        final FieldElement fieldElement2 = new Ed25519FieldElement(MathUtils.getField(), t2);

        // Act:
        final byte[] bytes1 = MathUtils.getField().getEncoding().encode(fieldElement1);
        final byte[] bytes2 = MathUtils.getField().getEncoding().encode(fieldElement2);

        // Assert:
        assertThat(bytes1, IsEqual.equalTo(MathUtils.toByteArray(BigInteger.ZERO)));
        assertThat(bytes2, IsEqual.equalTo(MathUtils.toByteArray(BigInteger.ONE)));
    }

    @Test
    public void encodeReturnsCorrectByteArray() {
        for (int i=0; i<10000; i++){
            // Arrange:
            final int[] t = new int[10];
            for (int j=0; j<10; j++) {
                t[j] = random.nextInt(1 << 28) - (1 << 27);
            }
            final FieldElement fieldElement1 = new Ed25519FieldElement(MathUtils.getField(), t);
            final BigInteger b = MathUtils.toBigInteger(t);

            // Act:
            final byte[] bytes = MathUtils.getField().getEncoding().encode(fieldElement1);

            // Assert:
            assertThat(bytes, IsEqual.equalTo(MathUtils.toByteArray(b.mod(MathUtils.getQ()))));
        }
    }

    @Test
    public void decodeReturnsCorrectFieldElementForSimpleByteArrays() {
        // Arrange:
        final byte[] bytes1 = new byte[32];
        final byte[] bytes2 = new byte[32];
        bytes2[0] = 1;

        // Act:
        final Ed25519FieldElement f1 = (Ed25519FieldElement)MathUtils.getField().getEncoding().decode(bytes1);
        final Ed25519FieldElement f2 = (Ed25519FieldElement)MathUtils.getField().getEncoding().decode(bytes2);
        final BigInteger b1 = MathUtils.toBigInteger(f1.t);
        final BigInteger b2 = MathUtils.toBigInteger(f2.t);

        // Assert:
        assertThat(b1, IsEqual.equalTo(BigInteger.ZERO));
        assertThat(b2, IsEqual.equalTo(BigInteger.ONE));
    }

    @Test
    public void decodeReturnsCorrectFieldElement() {
        for (int i=0; i<10000; i++) {
            // Arrange:
            final byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            bytes[31] = (byte)(bytes[31] & 0x7f);
            final BigInteger b1 = MathUtils.toBigInteger(bytes);

            // Act:
            final Ed25519FieldElement f = (Ed25519FieldElement)MathUtils.getField().getEncoding().decode(bytes);
            final BigInteger b2 = MathUtils.toBigInteger(f.t).mod(MathUtils.getQ());

            // Assert:
            assertThat(b2, IsEqual.equalTo(b1));
        }
    }

    @Test
    public void isNegativeReturnsCorrectResult() {
        for (int i=0; i<10000; i++) {
            // Arrange:
            final int[] t = new int[10];
            for (int j=0; j<10; j++) {
                t[j] = random.nextInt(1 << 28) - (1 << 27);
            }
            final boolean isNegative = MathUtils.toBigInteger(t).mod(MathUtils.getQ()).mod(new BigInteger("2")).equals(BigInteger.ONE);
            final FieldElement f = new Ed25519FieldElement(MathUtils.getField(), t);

            // Assert:
            assertThat(MathUtils.getField().getEncoding().isNegative(f), IsEqual.equalTo(isNegative));
        }
    }
}
