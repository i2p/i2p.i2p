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
 * A DNS label which contains more than just letters, digits and a hyphen.
 *
 */
public abstract class NonLdhLabel extends DnsLabel {

    protected NonLdhLabel(String label) {
        super(label);
    }

    protected static DnsLabel fromInternal(String label) {
        if (UnderscoreLabel.isUnderscoreLabelInternal(label)) {
            return new UnderscoreLabel(label);
        }

        if (LeadingOrTrailingHyphenLabel.isLeadingOrTrailingHypenLabelInternal(label)) {
            return new LeadingOrTrailingHyphenLabel(label);
        }

        return new OtherNonLdhLabel(label);
    }

}
