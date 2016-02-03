package net.i2p.crypto;

import java.math.BigInteger;
import java.security.spec.RSAKeyGenParameterSpec;

import net.i2p.util.NativeBigInteger;

/**
 * Constants for RSA
 *
 * @since 0.9.9
 */
final class RSAConstants {

    /**
     *  Generate a spec
     */
    private static RSAKeyGenParameterSpec genSpec(int size, BigInteger exp) {
        return new RSAKeyGenParameterSpec(size, exp);
    }

    private static final BigInteger F4 = new NativeBigInteger(RSAKeyGenParameterSpec.F4);

    // standard specs
    public static final RSAKeyGenParameterSpec F4_1024_SPEC = genSpec(1024, F4);
    public static final RSAKeyGenParameterSpec F4_2048_SPEC = genSpec(2048, F4);
    public static final RSAKeyGenParameterSpec F4_3072_SPEC = genSpec(3072, F4);
    public static final RSAKeyGenParameterSpec F4_4096_SPEC = genSpec(4096, F4);
}
