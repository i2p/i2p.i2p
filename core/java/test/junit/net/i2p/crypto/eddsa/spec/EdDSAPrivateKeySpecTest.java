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
package net.i2p.crypto.eddsa.spec;

import static org.hamcrest.Matchers.*;
import net.i2p.crypto.eddsa.Utils;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Assert;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * @author str4d
 *
 */
public class EdDSAPrivateKeySpecTest {
    static final byte[] ZERO_SEED = Utils.hexToBytes("0000000000000000000000000000000000000000000000000000000000000000");
    static final byte[] ZERO_H = Utils.hexToBytes("5046adc1dba838867b2bbbfdd0c3423e58b57970b5267a90f57960924a87f1960a6a85eaa642dac835424b5d7c8d637c00408c7a73da672b7f498521420b6dd3");
    static final byte[] ZERO_PK = Utils.hexToBytes("3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29");

    static final EdDSANamedCurveSpec ed25519 = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);

    /**
     * Test method for {@link net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec#EdDSAPrivateKeySpec(byte[], net.i2p.crypto.eddsa.spec.EdDSAParameterSpec)}.
     */
    @Test
    public void testEdDSAPrivateKeySpecFromSeed() {
        EdDSAPrivateKeySpec key = new EdDSAPrivateKeySpec(ZERO_SEED, ed25519);
        assertThat(key.getSeed(), is(equalTo(ZERO_SEED)));
        assertThat(key.getH(), is(equalTo(ZERO_H)));
        assertThat(key.getA().toByteArray(), is(equalTo(ZERO_PK)));
    }

    @Test
    public void incorrectSeedLengthThrows() {
        try {
            new EdDSAPrivateKeySpec(new byte[2], ed25519);
            Assert.fail("exception not thrown");
        } catch (IllegalArgumentException expected) {
            assertEquals("seed length is wrong", expected.getMessage());
        }
    }

    /**
     * Test method for {@link net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec#EdDSAPrivateKeySpec(net.i2p.crypto.eddsa.spec.EdDSAParameterSpec, byte[])}.
     */
    @Test
    public void testEdDSAPrivateKeySpecFromH() {
        EdDSAPrivateKeySpec key = new EdDSAPrivateKeySpec(ed25519, ZERO_H);
        assertThat(key.getSeed(), is(nullValue()));
        assertThat(key.getH(), is(equalTo(ZERO_H)));
        assertThat(key.getA().toByteArray(), is(equalTo(ZERO_PK)));
    }

    @Test
    public void incorrectHashLengthThrows() {
        try {
            new EdDSAPrivateKeySpec(ed25519, new byte[2]);
            fail("exception not thrown");
        } catch (IllegalArgumentException expected) {
            assertEquals("hash length is wrong", expected.getMessage());
        }
    }
}
