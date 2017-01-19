/*******************************************************************************
 * Copyright (c) 2001-2005 Sasa Markovic and Ciaran Treanor.
 * Copyright (c) 2011 The OpenNMS Group, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *******************************************************************************/
package org.jrobin.graph;

import org.jrobin.core.RrdException;
import org.jrobin.data.DataProcessor;

class CommentText implements RrdGraphConstants {
    private final String text; // original text

    String resolvedText; // resolved text
    String marker; // end-of-text marker
    boolean enabled; // hrule and vrule comments can be disabled at runtime
    int x, y; // coordinates, evaluated later

    CommentText(String text) {
        this.text = text;
    }

    void resolveText(DataProcessor dproc, ValueScaler valueScaler) throws RrdException {
        resolvedText = text;
        marker = "";
        if (resolvedText != null) {
            for (String mark : MARKERS) {
                if (resolvedText.endsWith(mark)) {
                    marker = mark;
                    resolvedText = resolvedText.substring(0, resolvedText.length() - marker.length());
                    trimIfGlue();
                    break;
                }
            }
        }
        enabled = resolvedText != null;
    }

    void trimIfGlue() {
        if (marker.equals(GLUE_MARKER)) {
            resolvedText = resolvedText.replaceFirst("\\s+$", "");
        }
    }

    boolean isPrint() {
        return false;
    }

    boolean isValidGraphElement() {
        return !isPrint() && enabled;
    }
}
