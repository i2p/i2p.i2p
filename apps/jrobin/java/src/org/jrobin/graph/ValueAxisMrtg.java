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

import org.jrobin.core.Util;

import java.awt.*;

class ValueAxisMrtg implements RrdGraphConstants {
	private ImageParameters im;
	private ImageWorker worker;
	private RrdGraphDef gdef;

	ValueAxisMrtg(RrdGraph rrdGraph) {
		this.im = rrdGraph.im;
		this.gdef = rrdGraph.gdef;
		this.worker = rrdGraph.worker;
		im.unit = gdef.unit;
	}

	boolean draw() {
		Font font = gdef.getFont(FONTTAG_AXIS);
		Paint mGridColor = gdef.colors[COLOR_MGRID];
		Paint fontColor = gdef.colors[COLOR_FONT];
		int labelOffset = (int) (worker.getFontAscent(font) / 2);

		if (Double.isNaN((im.maxval - im.minval) / im.magfact)) {
			return false;
		}

		int xLeft = im.xorigin;
		int xRight = im.xorigin + im.xsize;
		String labfmt;
		if (im.scaledstep / im.magfact * Math.max(Math.abs(im.quadrant), Math.abs(4 - im.quadrant)) <= 1.0) {
			labfmt = "%5.2f";
		}
		else {
			labfmt = Util.sprintf("%%4.%df", 1 - ((im.scaledstep / im.magfact > 10.0 || Math.ceil(im.scaledstep / im.magfact) == im.scaledstep / im.magfact) ? 1 : 0));
		}
		if (im.symbol != ' ' || im.unit != null) {
			labfmt += " ";
		}
		if (im.symbol != ' ') {
			labfmt += im.symbol;
		}
		if (im.unit != null) {
			labfmt += im.unit;
		}
		for (int i = 0; i <= 4; i++) {
			int y = im.yorigin - im.ysize * i / 4;
			if (y >= im.yorigin - im.ysize && y <= im.yorigin) {
				String graph_label = Util.sprintf(labfmt, im.scaledstep / im.magfact * (i - im.quadrant));
				int length = (int) (worker.getStringWidth(graph_label, font));
				worker.drawString(graph_label, xLeft - length - PADDING_VLABEL, y + labelOffset, font, fontColor);
				worker.drawLine(xLeft - 2, y, xLeft + 2, y, mGridColor, TICK_STROKE);
				worker.drawLine(xRight - 2, y, xRight + 2, y, mGridColor, TICK_STROKE);
				worker.drawLine(xLeft, y, xRight, y, mGridColor, GRID_STROKE);
			}
		}
		return true;
	}

}
