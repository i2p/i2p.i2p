package net.i2p.crypto;

import java.security.spec.AlgorithmParameterSpec;

import net.i2p.data.DataHelper;

/**
 * Defines the context for signing with personalized hashes.
 * See proposal 148.
 *
 * @since 0.9.40
 */
public enum SigContext {

    SC_NONE     (null),
    SC_DATAGRAM ("sign_datagramI2P"),
    SC_I2CP     ("I2CP_SessionConf"),
    SC_NETDB    ("network_database"),
    SC_NTCP     ("NTCP_1_handshake"),
    SC_SSU      ("SSUHandshakeSign"),
    SC_STREAMING("streaming_i2psig"),
    SC_SU3      ("i2pSU3FileFormat"),
    SC_TEST     ("test1234test5678"),

    ;

    private final SigContextSpec spec;

    /**
     * The 16 bytes for this type, or null for none
     */
    SigContext(String p) {
        spec = new SigContextSpec(p);
    }

    /**
     * The AlgorithmParameterSpec.
     * Pass this as an argument in setParameter()
     * to the Blake sign/verify engines.
     */
    public SigContextSpec getSpec() { return spec; }

    /**
     * The AlgorithmParameterSpec.
     * Pass this as an argument in setParameter()
     * to the Blake sign/verify engines.
     */
    public static class SigContextSpec implements AlgorithmParameterSpec {
        private final byte[] b;

        /**
         * The 16 bytes for this type, or null for none
         */
        public SigContextSpec(String p) {
            if (p != null) {
                b = DataHelper.getASCII(p);
                if (b.length != 16)
                    throw new IllegalArgumentException();
            } else {
                b = null;
            }
        }

        /**
         * The 16 bytes for this type, or null for none
         */
        public byte[] getData() { return b; }
    }
}
