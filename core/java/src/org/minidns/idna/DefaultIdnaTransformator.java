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

import java.net.IDN;

import org.minidns.dnsname.DnsName;

public class DefaultIdnaTransformator implements IdnaTransformator {

    @Override
    public String toASCII(String input) {
        // Special case if input is ".", i.e. a string containing only a single dot character. This is a workaround for
        // IDN.toASCII() implementations throwing an IllegalArgumentException on this input string (for example Android
        // APIs level 26, see https://issuetracker.google.com/issues/113070416).
        if (DnsName.ROOT.ace.equals(input)) {
            return DnsName.ROOT.ace;
        }

        return IDN.toASCII(input);
    }

    @Override
    public String toUnicode(String input) {
        return IDN.toUnicode(input);
    }

}
