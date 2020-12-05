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

/**
 * A reserved LDH label (R-LDH label), which have the property that they contain "--" in the third and fourth characters.
 *
 */
public class ReservedLdhLabel extends LdhLabel {

    protected ReservedLdhLabel(String label) {
        super(label);
        assert isReservedLdhLabelInternal(label);
    }

    public static boolean isReservedLdhLabel(String label) {
        if (!isLdhLabel(label)) {
            return false;
        }
        return isReservedLdhLabelInternal(label);
    }

    static boolean isReservedLdhLabelInternal(String label) {
        return label.length() >= 4
                && label.charAt(2) == '-'
                && label.charAt(3) == '-';
    }
}
