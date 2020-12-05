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
package org.minidns.idna;

public class MiniDnsIdna {

    private static IdnaTransformator idnaTransformator = new DefaultIdnaTransformator();

    public static String toASCII(String string) {
        return idnaTransformator.toASCII(string);
    }

    public static String toUnicode(String string) {
        return idnaTransformator.toUnicode(string);
    }

    public static void setActiveTransformator(IdnaTransformator idnaTransformator) {
        if (idnaTransformator == null) {
            throw new IllegalArgumentException();
        }
        MiniDnsIdna.idnaTransformator = idnaTransformator;
    }
}
