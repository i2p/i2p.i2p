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
package org.minidns.dnslabel;

/**
 * A LDH (<b>L</b>etters, <b>D</b>igits, <b>H</b>yphen) label, which is the
 * classical label form.
 * <p>
 * Note that it is a common misconception that LDH labels can not start with a
 * digit. The origin of this misconception is likely that
 * <a href="https://datatracker.ietf.org/doc/html/rfc1034#section-3.5">RFC 1034
 * § 3.5</a> specified
 * </p>
 * <blockquote>
 * They [i.e, DNS labels] must start with a letter, end with a letter or digit,
 * and have as interior characters only letters, digits, and hyphen.
 * </blockquote>.
 * However, this was relaxed in
 * <a href="https://datatracker.ietf.org/doc/html/rfc1123#page-13">RFC 1123 §
 * 2.1</a>
 * <blockquote>
 * One aspect of host name syntax is hereby changed: the restriction on the first
 * character is relaxed to allow either a letter or a digit.
 * </blockquote>
 * and later summarized in
 * <a href="https://datatracker.ietf.org/doc/html/rfc3696#section-2">RFC 3696 §
 * 2</a>:
 * <blockquote>
 * If the hyphen is used, it is not permitted to appear at either the beginning
 * or end of a label.
 * </blockquote>
 * Furthermore
 * <a href="https://datatracker.ietf.org/doc/html/rfc5890#section-2.3.1">RFC
 * 5890 § 2.3.1</a> only mentions the requirement that hyphen must not be the
 * first or last character of a LDH label.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5890#section-2.3.1">RFC 5890 §
 *      2.3.1. LDH Label</a>
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

        return consistsOnlyOfLettersDigitsAndHypen(label);
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
