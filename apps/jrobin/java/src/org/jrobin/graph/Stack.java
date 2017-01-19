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

import java.awt.*;

class Stack extends SourcedPlotElement {
	private final SourcedPlotElement parent;

	Stack(SourcedPlotElement parent, String srcName, Paint color) {
		super(srcName, color);
		this.parent = parent;
	}

	void assignValues(DataProcessor dproc) throws RrdException {
		double[] parentValues = parent.getValues();
		double[] procValues = dproc.getValues(srcName);
		values = new double[procValues.length];
		for (int i = 0; i < values.length; i++) {
			values[i] = parentValues[i] + procValues[i];
		}
	}

	float getParentLineWidth() {
		if (parent instanceof Line) {
			return ((Line) parent).width;
		}
		else if (parent instanceof Area) {
			return -1F;
		}
		else /* if(parent instanceof Stack) */ {
			return ((Stack) parent).getParentLineWidth();
		}
	}

	Paint getParentColor() {
		return parent.color;
	}
}
