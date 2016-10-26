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
import org.jrobin.core.Util;
import org.jrobin.data.DataProcessor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

class PrintText extends CommentText {
	static final String UNIT_MARKER = "([^%]?)%(s|S)";
	static final Pattern UNIT_PATTERN = Pattern.compile(UNIT_MARKER);

	private final String srcName, consolFun;
	private final boolean includedInGraph;

	PrintText(String srcName, String consolFun, String text, boolean includedInGraph) {
		super(text);
		this.srcName = srcName;
		this.consolFun = consolFun;
		this.includedInGraph = includedInGraph;
	}

	boolean isPrint() {
		return !includedInGraph;
	}

	void resolveText(DataProcessor dproc, ValueScaler valueScaler) throws RrdException {
		super.resolveText(dproc, valueScaler);
		if (resolvedText != null) {
			double value = dproc.getAggregate(srcName, consolFun);
			Matcher matcher = UNIT_PATTERN.matcher(resolvedText);
			if (matcher.find()) {
				// unit specified
				ValueScaler.Scaled scaled = valueScaler.scale(value, matcher.group(2).equals("s"));
				resolvedText = resolvedText.substring(0, matcher.start()) +
						matcher.group(1) + scaled.unit + resolvedText.substring(matcher.end());
				value = scaled.value;
			}
			resolvedText = Util.sprintf(resolvedText, value);
			trimIfGlue();
		}
	}
}
