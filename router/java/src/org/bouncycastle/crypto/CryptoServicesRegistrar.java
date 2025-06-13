package org.bouncycastle.crypto;

import java.security.SecureRandom;

public class CryptoServicesRegistrar {

    public static void checkConstraints(CryptoServiceProperties csp) {}

    private static final SecureRandom sr = new SecureRandom();

    /**
     * Return the default source of randomness.
     *
     * @return the default SecureRandom
     */
    public static SecureRandom getSecureRandom(SecureRandom secureRandom)
    {
        return null == secureRandom ? sr : secureRandom;
    }
}
