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
 * A Non-LDH label which does <em>not</em> begin with an underscore ('_'), hyphen ('-') or ends with an hyphen.
 *
 */
public final class OtherNonLdhLabel extends NonLdhLabel {

    protected OtherNonLdhLabel(String label) {
        super(label);
    }

}
