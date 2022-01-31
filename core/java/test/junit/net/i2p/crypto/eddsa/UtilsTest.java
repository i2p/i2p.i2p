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

import org.hamcrest.core.IsEqual;
import org.junit.*;

import java.security.SecureRandom;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author str4d
 * additional test by the NEM project team.
 *
 */
public class UtilsTest {
    private static final String hex1 = "3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29";
    private static final String hex2 = "47a3f5b71494bcd961f3a4e859a238d6eaf8e648746d2f56a89b5e236f98d45f";
    private static final String hex3 = "5fd396e4a2b5dc9078f57e3ab5a87c28fd128e5f78cc4a97f4122dc45f6e4bb9";
    private static final byte[] bytes1 = { 59, 106, 39, -68, -50, -74, -92, 45, 98, -93, -88, -48, 42, 111, 13, 115,
                                           101, 50, 21, 119, 29, -30, 67, -90, 58, -64, 72, -95, -117, 89, -38, 41 };
    private static final byte[] bytes2 = { 71, -93, -11, -73, 20, -108, -68, -39, 97, -13, -92, -24, 89, -94, 56, -42,
                                           -22, -8, -26, 72, 116, 109, 47, 86, -88, -101, 94, 35, 111, -104, -44, 95 };
    private static final byte[] bytes3 = { 95, -45, -106, -28, -94, -75, -36, -112, 120, -11, 126, 58, -75, -88, 124, 40,
                                           -3, 18, -114, 95, 120, -52, 74, -105, -12, 18, 45, -60, 95, 110, 75, -71 };

    /**
     * Test method for {@link net.i2p.crypto.eddsa.Utils#equal(int, int)}.
     */
    @Test
    public void testIntEqual() {
        assertThat(Utils.equal(0, 0),       is(1));
        assertThat(Utils.equal(1, 1),       is(1));
        assertThat(Utils.equal(1, 0),       is(0));
        assertThat(Utils.equal(1, 127),     is(0));
        assertThat(Utils.equal(-127, 127),  is(0));
        assertThat(Utils.equal(-42, -42),   is(1));
        assertThat(Utils.equal(255, 255),   is(1));
        assertThat(Utils.equal(-255, -256), is(0));
    }

    @Test
    public void equalsReturnsOneForEqualByteArrays() {
        final SecureRandom random = new SecureRandom();
        final byte[] bytes1 = new byte[32];
        final byte[] bytes2 = new byte[32];
        for (int i=0; i<100; i++) {
            random.nextBytes(bytes1);
            System.arraycopy(bytes1, 0, bytes2, 0, 32);
            assertThat(Utils.equal(bytes1, bytes2), IsEqual.equalTo(1));
        }
    }

    @Test
    public void equalsReturnsZeroForUnequalByteArrays() {
        final SecureRandom random = new SecureRandom();
        final byte[] bytes1 = new byte[32];
        final byte[] bytes2 = new byte[32];
        random.nextBytes(bytes1);
        for (int i=0; i<32; i++) {
            System.arraycopy(bytes1, 0, bytes2, 0, 32);
            bytes2[i] = (byte)(bytes2[i] ^ 0xff);
            assertThat(Utils.equal(bytes1, bytes2), IsEqual.equalTo(0));
        }
    }

    /**
     * Test method for {@link net.i2p.crypto.eddsa.Utils#equal(byte[], byte[])}.
     */
    @Test
    public void testByteArrayEqual() {
        byte[] zero = new byte[32];
        byte[] one = new byte[32];
        one[0] = 1;

        assertThat(Utils.equal(zero, zero), is(1));
        assertThat(Utils.equal(one, one),   is(1));
        assertThat(Utils.equal(one, zero),  is(0));
        assertThat(Utils.equal(zero, one),  is(0));
    }

    /**
     * Test method for {@link net.i2p.crypto.eddsa.Utils#negative(int)}.
     */
    @Test
    public void testNegative() {
        assertThat(Utils.negative(0),    is(0));
        assertThat(Utils.negative(1),    is(0));
        assertThat(Utils.negative(-1),   is(1));
        assertThat(Utils.negative(32),   is(0));
        assertThat(Utils.negative(-100), is(1));
        assertThat(Utils.negative(127),  is(0));
        assertThat(Utils.negative(-255), is(1));
    }

    /**
     * Test method for {@link net.i2p.crypto.eddsa.Utils#bit(byte[], int)}.
     */
    @Test
    public void testBit() {
        assertThat(Utils.bit(new byte[] {0}, 0), is(0));
        assertThat(Utils.bit(new byte[] {8}, 3), is(1));
        assertThat(Utils.bit(new byte[] {1, 2, 3}, 9), is(1));
        assertThat(Utils.bit(new byte[] {1, 2, 3}, 15), is(0));
        assertThat(Utils.bit(new byte[] {1, 2, 3}, 16), is(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void hexToBytesThrowsOnInvalidLengthHexString() {
        Utils.hexToBytes("bad");
    }

    @Test
    public void hexToBytesReturnsCorrectByteArray() {
        assertThat(Utils.hexToBytes(hex1), IsEqual.equalTo(bytes1));
        assertThat(Utils.hexToBytes(hex2), IsEqual.equalTo(bytes2));
        assertThat(Utils.hexToBytes(hex3), IsEqual.equalTo(bytes3));
    }

    @Test
    public void bytesToHexReturnsCorrectHexString() {
        assertThat(Utils.bytesToHex(bytes1), IsEqual.equalTo(hex1));
        assertThat(Utils.bytesToHex(bytes2), IsEqual.equalTo(hex2));
        assertThat(Utils.bytesToHex(bytes3), IsEqual.equalTo(hex3));
    }
}
