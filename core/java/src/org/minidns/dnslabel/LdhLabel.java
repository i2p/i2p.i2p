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
 * A LDH (<b>L</b>etters, <b>D</b>igits, <b>H</b>yphen) label, which is the classical label form.
 * 
 * @see <a href="https://tools.ietf.org/html/rfc5890#section-2.3.1">RFC 5890 § 2.3.1. LDH Label</a>
 *
 */
public abstract class LdhLabel extends DnsLabel {

    protected LdhLabel(String label) {
        super(label);
    }

    public static boolean isLdhLabel(String label) {
        if (label.isEmpty()) {
            return false;
        }

        if (LeadingOrTrailingHyphenLabel.isLeadingOrTrailingHypenLabelInternal(label)) {
            return false;
        }

        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-') {
                continue;
            }
            return false;
        }
        return true;
    }

    protected static LdhLabel fromInternal(String label) {
        assert isLdhLabel(label);

        if (ReservedLdhLabel.isReservedLdhLabel(label)) {
            // Label starts with '??--'. Now let us see if it is a XN-Label, starting with 'xn--', but be aware that the
            // 'xn' part is case insensitive. The XnLabel.isXnLabelInternal(String) method takes care of this.
            if (XnLabel.isXnLabelInternal(label)) {
                return XnLabel.fromInternal(label);
            } else {
                return new ReservedLdhLabel(label);
            }
        }
        return new NonReservedLdhLabel(label);
    }
}
