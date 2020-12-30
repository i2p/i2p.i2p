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
 * Very minimal Base64 encoder.
 */
public final class Base64 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String PADDING = "==";

    /**
     * Do not allow to instantiate Base64
     */
    private Base64() {
    }

    public static String encodeToString(byte[] bytes) {
        int paddingCount = (3 - (bytes.length % 3)) % 3;
        byte[] padded = new byte[bytes.length + paddingCount];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i += 3) {
            int j = ((padded[i] & 0xff) << 16) + ((padded[i + 1] & 0xff) << 8) + (padded[i + 2] & 0xff);
            sb.append(ALPHABET.charAt((j >> 18) & 0x3f)).append(ALPHABET.charAt((j >> 12) & 0x3f))
                    .append(ALPHABET.charAt((j >> 6) & 0x3f)).append(ALPHABET.charAt(j & 0x3f));
        }
        return sb.substring(0, sb.length() - paddingCount) + PADDING.substring(0, paddingCount);
    }
}
