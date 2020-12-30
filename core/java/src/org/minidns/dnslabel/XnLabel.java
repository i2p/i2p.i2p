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
package org.minidns.dnslabel;

import java.util.Locale;

import org.minidns.idna.MiniDnsIdna;

/**
 * A label that begins with "xn--" and follows the LDH rule.
 */
public abstract class XnLabel extends ReservedLdhLabel {

    protected XnLabel(String label) {
        super(label);
    }

    protected static LdhLabel fromInternal(String label) {
        assert isIdnAcePrefixed(label);

        String uLabel = MiniDnsIdna.toUnicode(label);
        if (label.equals(uLabel)) {
            // No Punycode conversation to Unicode was performed, this is a fake A-label!
            return new FakeALabel(label);
        } else {
            return new ALabel(label);
        }
    }

    public static boolean isXnLabel(String label) {
        if (!isLdhLabel(label)) {
            return false;
        }
        return isXnLabelInternal(label);
    }

    static boolean isXnLabelInternal(String label) {
        return label.substring(0, 2).toLowerCase(Locale.US).equals("xn");
    }
}
