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

class TimeAxisSetting {
	final long secPerPix;
	final int minorUnit, minorUnitCount, majorUnit, majorUnitCount;
	final int labelUnit, labelUnitCount, labelSpan;
	final String format;

	TimeAxisSetting(long secPerPix, int minorUnit, int minorUnitCount, int majorUnit, int majorUnitCount,
					int labelUnit, int labelUnitCount, int labelSpan, String format) {
		this.secPerPix = secPerPix;
		this.minorUnit = minorUnit;
		this.minorUnitCount = minorUnitCount;
		this.majorUnit = majorUnit;
		this.majorUnitCount = majorUnitCount;
		this.labelUnit = labelUnit;
		this.labelUnitCount = labelUnitCount;
		this.labelSpan = labelSpan;
		this.format = format;
	}

	TimeAxisSetting(TimeAxisSetting s) {
		this.secPerPix = s.secPerPix;
		this.minorUnit = s.minorUnit;
		this.minorUnitCount = s.minorUnitCount;
		this.majorUnit = s.majorUnit;
		this.majorUnitCount = s.majorUnitCount;
		this.labelUnit = s.labelUnit;
		this.labelUnitCount = s.labelUnitCount;
		this.labelSpan = s.labelSpan;
		this.format = s.format;
	}

	TimeAxisSetting(int minorUnit, int minorUnitCount, int majorUnit, int majorUnitCount,
					int labelUnit, int labelUnitCount, int labelSpan, String format) {
		this(0, minorUnit, minorUnitCount, majorUnit, majorUnitCount,
				labelUnit, labelUnitCount, labelSpan, format);
	}

}
