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
 * A DNS label which begins with an underscore ('_').
 *
 */
public final class UnderscoreLabel extends NonLdhLabel {

    protected UnderscoreLabel(String label) {
        super(label);
    }

    protected static boolean isUnderscoreLabelInternal(String label) {
        return label.charAt(0) == '_';
    }
}
