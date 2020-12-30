/*
 * Copyright 2015-2020 the original author or authors
 *
 * This software is licensed under the Apache License, Version 2.0,
 * the GNU Lesser General Public License version 2 or later ("LGPL")
 * and the WTFPL.
 * You may choose either license to govern your use of this software only
 * upon the condition that you accept all of the terms of either
 * the Apache License 2.0, the LGPL 2.1+ or the WTFPL.
 */
package org.minidns.constants;

import java.util.HashMap;
import java.util.Map;

public final class DnssecConstants {
    /**
     * Do not allow to instantiate DNSSECConstants
     */
    private DnssecConstants() {
    }

    private static final Map<Byte, SignatureAlgorithm> SIGNATURE_ALGORITHM_LUT = new HashMap<>();

    /**
     * DNSSEC Signature Algorithms.
     * 
     * @see <a href=
     *      "http://www.iana.org/assignments/dns-sec-alg-numbers/dns-sec-alg-numbers.xhtml">
     *      IANA DNSSEC Algorithm Numbers</a>
     */
    public enum SignatureAlgorithm {
        @Deprecated
        RSAMD5(1, "RSA/MD5"),
        DH(2, "Diffie-Hellman"),
        DSA(3, "DSA/SHA1"),
        RSASHA1(5, "RSA/SHA-1"),
        DSA_NSEC3_SHA1(6, "DSA_NSEC3-SHA1"),
        RSASHA1_NSEC3_SHA1(7, "RSASHA1-NSEC3-SHA1"),
        RSASHA256(8, "RSA/SHA-256"),
        RSASHA512(10, "RSA/SHA-512"),
        ECC_GOST(12, "GOST R 34.10-2001"),
        ECDSAP256SHA256(13, "ECDSA Curve P-256 with SHA-256"),
        ECDSAP384SHA384(14, "ECDSA Curve P-384 with SHA-384"),
        INDIRECT(252, "Reserved for Indirect Keys"),
        PRIVATEDNS(253, "private algorithm"),
        PRIVATEOID(254, "private algorithm oid"),
        ;

        SignatureAlgorithm(int number, String description) {
            if (number < 0 || number > 255) {
                throw new IllegalArgumentException();
            }
            this.number = (byte) number;
            this.description = description;
            SIGNATURE_ALGORITHM_LUT.put(this.number, this);
        }

        public final byte number;
        public final String description;

        public static SignatureAlgorithm forByte(byte b) {
            return SIGNATURE_ALGORITHM_LUT.get(b);
        }
    }

    private static final Map<Byte, DigestAlgorithm> DELEGATION_DIGEST_LUT = new HashMap<>();

    /**
     * DNSSEC Digest Algorithms.
     * 
     * @see <a href=
     *      "https://www.iana.org/assignments/ds-rr-types/ds-rr-types.xhtml">
     *      IANA Delegation Signer (DS) Resource Record (RR)</a>
     */
    public enum DigestAlgorithm {
        SHA1(1, "SHA-1"),
        SHA256(2, "SHA-256"),
        GOST(3, "GOST R 34.11-94"),
        SHA384(4, "SHA-384"),
        ;

        DigestAlgorithm(int value, String description) {
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException();
            }
            this.value = (byte) value;
            this.description = description;
            DELEGATION_DIGEST_LUT.put(this.value, this);
        }

        public final byte value;
        public final String description;

        public static DigestAlgorithm forByte(byte b) {
            return DELEGATION_DIGEST_LUT.get(b);
        }
    }
}
