package net.i2p.crypto.eddsa.math.bigint;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.math.BigInteger;

import net.i2p.crypto.eddsa.Utils;
import net.i2p.crypto.eddsa.math.Field;
import net.i2p.crypto.eddsa.math.FieldElement;
import org.junit.Test;

/**
 * @author str4d
 *
 */
public class BigIntegerFieldElementTest {
    static final byte[] BYTES_ZERO = Utils.hexToBytes("0000000000000000000000000000000000000000000000000000000000000000");
    static final byte[] BYTES_ONE = Utils.hexToBytes("0100000000000000000000000000000000000000000000000000000000000000");
    static final byte[] BYTES_TEN = Utils.hexToBytes("0a00000000000000000000000000000000000000000000000000000000000000");

    static final Field ed25519Field = new Field(
            256, // b
            Utils.hexToBytes("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"), // q
            new BigIntegerLittleEndianEncoding());

    static final FieldElement ZERO = new BigIntegerFieldElement(ed25519Field, BigInteger.ZERO);
    static final FieldElement ONE = new BigIntegerFieldElement(ed25519Field, BigInteger.ONE);
    static final FieldElement TWO = new BigIntegerFieldElement(ed25519Field, BigInteger.valueOf(2));

    /**
     * Test method for {@link FieldElement#FieldElement(java.math.BigInteger)}.
     */
    @Test
    public void testFieldElementBigInteger() {
        assertThat(new BigIntegerFieldElement(ed25519Field, BigInteger.ZERO).bi, is(BigInteger.ZERO));
        assertThat(new BigIntegerFieldElement(ed25519Field, BigInteger.ONE).bi, is(BigInteger.ONE));
        assertThat(new BigIntegerFieldElement(ed25519Field, BigInteger.valueOf(2)).bi, is(BigInteger.valueOf(2)));
    }

    /**
     * Test method for {@link FieldElement#toByteArray()}.
     */
    @Test
    public void testToByteArray() {
        byte[] zero = ZERO.toByteArray();
        assertThat(zero.length, is(equalTo(BYTES_ZERO.length)));
        assertThat(zero, is(equalTo(BYTES_ZERO)));

        byte[] one = ONE.toByteArray();
        assertThat(one.length, is(equalTo(BYTES_ONE.length)));
        assertThat(one, is(equalTo(BYTES_ONE)));

        byte[] ten = new BigIntegerFieldElement(ed25519Field, BigInteger.TEN).toByteArray();
        assertThat(ten.length, is(equalTo(BYTES_TEN.length)));
        assertThat(ten, is(equalTo(BYTES_TEN)));
    }

    /**
     * Test method for {@link FieldElement#isNonZero()}.
     */
    @Test
    public void testIsNonZero() {
        fail("Not yet implemented");
    }

    /**
     * Test method for {@link FieldElement#isNegative()}.
     */
    @Test
    public void testIsNegative() {
        fail("Not yet implemented");
    }

    /**
     * Test method for {@link FieldElement#add(FieldElement)}.
     */
    @Test
    public void testAdd() {
        fail("Not yet implemented");
    }

    /**
     * Test method for {@link FieldElement#subtract(FieldElement)}.
     */
    @Test
    public void testSubtract() {
        fail("Not yet implemented");
    }

    /**
     * Test method for {@link FieldElement#negate()}.
     */
    @Test
    public void testNegate() {
        fail("Not yet implemented");
    }

    /**
     * Test method for {@link FieldElement#multiply(FieldElement)}.
     */
    @Test
    public void testMultiply() {
        fail("Not yet implemented");
    }

    /**
     * Test method for {@link FieldElement#square()}.
     */
    @Test
    public void testSquare() {
        fail("Not yet implemented");
    }

    /**
     * Test method for {@link FieldElement#squareAndDouble()}.
     */
    @Test
    public void testSquareAndDouble() {
        fail("Not yet implemented");
    }

    /**
     * Test method for {@link FieldElement#invert()}.
     */
    @Test
    public void testInvert() {
        fail("Not yet implemented");
    }

    /**
     * Test method for {@link FieldElement#modPow(java.math.BigInteger, java.math.BigInteger)}.
     */
    @Test
    public void testModPow() {
        fail("Not yet implemented");
    }

    /**
     * Test method for {@link FieldElement#equals(java.lang.Object)}.
     */
    @Test
    public void testEqualsObject() {
        assertThat(new BigIntegerFieldElement(ed25519Field, BigInteger.ZERO), is(equalTo(ZERO)));
        //assertThat(new BigIntegerFieldElement(ed25519Field, BYTES_ZERO), is(equalTo(ZERO)));
        assertThat(new BigIntegerFieldElement(ed25519Field, BigInteger.valueOf(1000)), is(equalTo(new BigIntegerFieldElement(ed25519Field, BigInteger.valueOf(1000)))));
        assertThat(ONE, is(not(equalTo(TWO))));
    }

}
