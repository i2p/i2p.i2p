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
 * A DNS label with a leading or trailing hyphen ('-').
 */
public final class LeadingOrTrailingHyphenLabel extends NonLdhLabel {

    protected LeadingOrTrailingHyphenLabel(String label) {
        super(label);
    }

    protected static boolean isLeadingOrTrailingHypenLabelInternal(String label) {
        if (label.isEmpty()) {
            return false;
        }

        if (label.charAt(0) == '-') {
            return true;
        }

        if (label.charAt(label.length() - 1) == '-') {
            return true;
        }

        return false;
    }
}
