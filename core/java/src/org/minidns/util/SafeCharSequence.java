/*
 * Copyright 2015-2024 the original author or authors
 *
 * This software is licensed under the Apache License, Version 2.0,
 * the GNU Lesser General Public License version 2 or later ("LGPL")
 * and the WTFPL.
 * You may choose either license to govern your use of this software only
 * upon the condition that you accept all of the terms of either
 * the Apache License 2.0, the LGPL 2.1+ or the WTFPL.
 */
package org.minidns.util;

public class SafeCharSequence implements CharSequence {

    @Override
    public final int length() {
        return toSafeString().length();
    }

    @Override
    public final char charAt(int index) {
        return toSafeString().charAt(index);
    }

    @Override
    public final CharSequence subSequence(int start, int end) {
        return toSafeString().subSequence(end, end);
    }

    public String toSafeString() {
        // The default implementation assumes that toString() returns a safe
        // representation. Subclasses may override toSafeString() if this assumption is
        // not correct.
        return toString();
    }
}
