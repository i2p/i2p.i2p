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

import java.awt.*;

class SourcedPlotElement extends PlotElement {
	final String srcName;
	double[] values;

	SourcedPlotElement(String srcName, Paint color) {
		super(color);
		this.srcName = srcName;
	}

	void assignValues(DataProcessor dproc) throws RrdException {
		values = dproc.getValues(srcName);
	}

	double[] getValues() {
		return values;
	}

	double getMinValue() {
		return Util.min(values);
	}

	double getMaxValue() {
		return Util.max(values);
	}
}
