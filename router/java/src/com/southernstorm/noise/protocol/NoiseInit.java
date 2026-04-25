package com.southernstorm.noise.protocol;

import java.security.MessageDigest;
import java.util.Arrays;

import net.i2p.data.DataHelper;

/**
 * I2P refactor to make Pattern IDs enums
 * Pulled out from HandshakeState and SymmetricState
 *
 * @since 0.9.70
 */
public class NoiseInit {
        
        private NoiseInit() {}

        /** NTCP2 */
        private static final String protocolName = "Noise_XKaesobfse+hs2+hs3_25519_ChaChaPoly_SHA256";
        /** Ratchet */
        private static final String protocolName2 = "Noise_IKelg2+hs2_25519_ChaChaPoly_SHA256";
        /** Tunnels */
        private static final String protocolName3 = "Noise_N_25519_ChaChaPoly_SHA256";
        /** SSU2 */
        private static final String protocolName4 = "Noise_XKchaobfse+hs1+hs2+hs3_25519_ChaChaPoly_SHA256";
        /**
         * Hybrid Ratchet
         * @since 0.9.67
         */
        private static final String protocolName5 = "Noise_IKhfselg2_25519+MLKEM512_ChaChaPoly_SHA256";
        private static final String protocolName6 = "Noise_IKhfselg2_25519+MLKEM768_ChaChaPoly_SHA256";
        private static final String protocolName7 = "Noise_IKhfselg2_25519+MLKEM1024_ChaChaPoly_SHA256";
        /**
         * Hybrid NTCP2
         * @since 0.9.69
         */
        private static final String protocolName8 = "Noise_XKhfsaesobfse+hs2+hs3_25519+MLKEM512_ChaChaPoly_SHA256";
        private static final String protocolName9 = "Noise_XKhfsaesobfse+hs2+hs3_25519+MLKEM768_ChaChaPoly_SHA256";
        private static final String protocolName10 = "Noise_XKhfsaesobfse+hs2+hs3_25519+MLKEM1024_ChaChaPoly_SHA256";
        /**
         * Hybrid SSU2
         * @since 0.9.69
         */
        private static final String protocolName11 = "Noise_XKhfschaobfse+hs1+hs2+hs3_25519+MLKEM512_ChaChaPoly_SHA256";
        private static final String protocolName12 = "Noise_XKhfschaobfse+hs1+hs2+hs3_25519+MLKEM768_ChaChaPoly_SHA256";

        /** NTCP2 */
        private static final String PATTERN_ID_XK = "XK";
        /** Ratchet */
        private static final String PATTERN_ID_IK = "IK";
        /** Tunnels */
        private static final String PATTERN_ID_N = "N";
        /** same as N but no post-mixHash needed */
        private static final String PATTERN_ID_N_NO_RESPONSE = "N!";
        /** SSU2 */
        private static final String PATTERN_ID_XK_SSU2 = "XK-SSU2";
        /** Hybrid Base */
        private static final String PATTERN_ID_IKHFS = "IKhfs";
        private static final String PATTERN_ID_XKHFS = "XKhfs";
        /**
         * Hybrid Ratchet
         * @since 0.9.67
         */
        private static final String PATTERN_ID_IKHFS_512 = "IKhfs512";
        private static final String PATTERN_ID_IKHFS_768 = "IKhfs768";
        private static final String PATTERN_ID_IKHFS_1024 = "IKhfs1024";
        /**
         * Hybrid NTCP2
         * @since 0.9.69
         */
        private static final String PATTERN_ID_XKHFS_512 = "XKhfs512";
        private static final String PATTERN_ID_XKHFS_768 = "XKhfs768";
        private static final String PATTERN_ID_XKHFS_1024 = "XKhfs1024";
        /**
         * Hybrid SSU2
         * @since 0.9.69
         */
        private static final String PATTERN_ID_XKHFS_512_SSU2 = "XKhfs512-SSU2";
        private static final String PATTERN_ID_XKHFS_768_SSU2 = "XKhfs768-SSU2";
        // no 1024, too big

        /**
         *
         */
        public enum PatternID {
            XK(PATTERN_ID_XK, protocolName),
            IK(PATTERN_ID_XK, protocolName2),
            N(PATTERN_ID_XK, protocolName3),
            N_NO_RESPONSE(PATTERN_ID_XK, protocolName3),
            XK_SSU2(PATTERN_ID_XK, protocolName4),
            IKHFS_512(PATTERN_ID_XK, protocolName5),
            IKHFS_768(PATTERN_ID_XK, protocolName6),
            IKHFS_1024(PATTERN_ID_XK, protocolName7),
            XKHFS_512(PATTERN_ID_XK, protocolName8),
            XKHFS_768(PATTERN_ID_XK, protocolName9),
            XKHFS_1024(PATTERN_ID_XK, protocolName10),
            XKHFS_512_SSU2(PATTERN_ID_XK, protocolName11),
            XKHFS_768_SSU2(PATTERN_ID_XK, protocolName12);

            private final String prefix;
            private final String patternId;
            private final String protoName;
            private final String dh;
            private final String scipher;
            private final String shash;
            private short[] pattern;
            private final byte[] ck;
            private final byte[] h;

            /**
             *  Parse the protocol name into its components, validate,
             *  lookup the Pattern,
             *  and generate initial CK and H
             */
            PatternID(String pid, String protocolName) {
                patternId = pid;
                protoName = protocolName;

                String[] components = protocolName.split("_");
                if (components.length != 5)
                        throw new IllegalArgumentException("Protocol name must have 5 components");
                prefix = components[0];
                dh = components[2];
                scipher = components[3];
                shash = components[4];
                if (!prefix.equals("Noise") && !prefix.equals("NoisePSK"))
                        throw new IllegalArgumentException("Prefix must be Noise or NoisePSK");
                String id = components[1];
                if (id.length() >= 5) {
                    if (id.substring(2, 5).equals("hfs"))
                        id = id.substring(0, 5);
                    else
                        id = id.substring(0, 2);

                }
                pattern = Pattern.lookup(id);
                if (pattern == null)
                    throw new IllegalArgumentException("Handshake pattern " + id + " is not recognized");

                ck = initHash(protoName, shash);
                h = new byte[ck.length];
                try {
                    MessageDigest md = Noise.createHash(shash);
                    md.update(ck, 0, ck.length);
                    md.digest(h, 0, ck.length);
                    Noise.releaseHash(md);
                } catch (Exception e) {
                    throw new IllegalArgumentException(e);
                }
            }

            public String getPrefix() { return prefix; }
            public String getPatternID() { return patternId; }
            public String getProtocolName() { return protoName; }
            public String getDH() { return dh; }
            public String getCipher() { return scipher; }
            public String getHash() { return shash; }
            public short[] getPattern() { return pattern; }

            /**
             *  @return a copy
             */
            public byte[] getInitialCK() { return Arrays.copyOf(ck, ck.length); }

            /**
             *  @return a copy
             */
            public byte[] getInitialH() { return Arrays.copyOf(h, h.length); }


        }

        /**
         * Moved from SymmetricState
         */
        private static byte[] initHash(String protocolName, String shash) {
            byte[] protocolNameBytes = DataHelper.getUTF8(protocolName);
            byte[] rv = new byte[32];
            if (protocolNameBytes.length <= 32) {
                System.arraycopy(protocolNameBytes, 0, rv, 0, protocolNameBytes.length);
                Arrays.fill(rv, protocolNameBytes.length, 32, (byte)0);
            } else {
                try {
                    MessageDigest hash = Noise.createHash(shash);
                    hash.update(protocolNameBytes, 0, protocolNameBytes.length);
                    hash.digest(rv, 0, 32);
                    Noise.releaseHash(hash);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return rv;
        }
}
