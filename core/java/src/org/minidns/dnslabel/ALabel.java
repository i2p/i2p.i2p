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

import org.minidns.idna.MiniDnsIdna;

public final class ALabel extends XnLabel {

    protected ALabel(String label) {
        super(label);
    }

    @Override
    protected String getInternationalizedRepresentationInternal() {
        return MiniDnsIdna.toUnicode(label);
    }
}
