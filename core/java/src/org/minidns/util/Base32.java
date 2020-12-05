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
package org.minidns.util;

/**
 * Very minimal Base32 encoder.
 */
public final class Base32 {
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUV";
    private static final String PADDING = "======";

    /**
     * Do not allow to instantiate Base32
     */
    private Base32() {
    }

    public static String encodeToString(byte[] bytes) {
        int paddingCount = (int) (8 - (bytes.length % 5) * 1.6) % 8;
        byte[] padded = new byte[bytes.length + paddingCount];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i += 5) {
            long j = ((long) (padded[i] & 0xff) << 32) + ((long) (padded[i + 1] & 0xff) << 24)
                    + ((padded[i + 2] & 0xff) << 16) + ((padded[i + 3] & 0xff) << 8) + (padded[i + 4] & 0xff);
            sb.append(ALPHABET.charAt((int) ((j >> 35) & 0x1f))).append(ALPHABET.charAt((int) ((j >> 30) & 0x1f)))
                    .append(ALPHABET.charAt((int) ((j >> 25) & 0x1f))).append(ALPHABET.charAt((int) ((j >> 20) & 0x1f)))
                    .append(ALPHABET.charAt((int) ((j >> 15) & 0x1f))).append(ALPHABET.charAt((int) ((j >> 10) & 0x1f)))
                    .append(ALPHABET.charAt((int) ((j >> 5) & 0x1f))).append(ALPHABET.charAt((int) (j & 0x1f)));
        }
        return sb.substring(0, sb.length() - paddingCount) + PADDING.substring(0, paddingCount);
    }
}
