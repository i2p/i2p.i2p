package net.i2p.crypto.eddsa.spec;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import net.i2p.crypto.eddsa.Utils;

import org.junit.Test;

/**
 * @author str4d
 *
 */
public class EdDSAPrivateKeySpecTest {
    static final byte[] ZERO_SEED = Utils.hexToBytes("0000000000000000000000000000000000000000000000000000000000000000");
    static final byte[] ZERO_PK = Utils.hexToBytes("3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29");

    static final EdDSANamedCurveSpec ed25519 = EdDSANamedCurveTable.getByName("ed25519-sha-512");

    /**
     * Test method for {@link net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec#EdDSAPrivateKeySpec(byte[], net.i2p.crypto.eddsa.spec.EdDSAParameterSpec)}.
     */
    @Test
    public void testEdDSAPrivateKeySpecFromSeed() {
        EdDSAPrivateKeySpec key = new EdDSAPrivateKeySpec(ZERO_SEED, ed25519);
        assertThat(key.getSeed(), is(equalTo(ZERO_SEED)));
        assertThat(key.getA().toByteArray(), is(equalTo(ZERO_PK)));
    }

}
