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

class ValueAxisLogarithmic implements RrdGraphConstants {
	private static final double[][] yloglab = {
			{1e9, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
			{1e3, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
			{1e1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
			/* {  1e1, 1,  5,  0,  0,  0,  0,  0,  0,  0,  0,  0 }, */
			{1e1, 1, 2.5, 5, 7.5, 0, 0, 0, 0, 0, 0, 0},
			{1e1, 1, 2, 4, 6, 8, 0, 0, 0, 0, 0, 0},
			{1e1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 0},
			{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
	};

	private RrdGraph rrdGraph;
	private ImageParameters im;
	private ImageWorker worker;
	private RrdGraphDef gdef;

	ValueAxisLogarithmic(RrdGraph rrdGraph) {
		this.rrdGraph = rrdGraph;
		this.im = rrdGraph.im;
		this.gdef = rrdGraph.gdef;
		this.worker = rrdGraph.worker;
	}

	boolean draw() {
		Font font = gdef.getFont(FONTTAG_AXIS);
		Paint gridColor = gdef.colors[COLOR_GRID];
		Paint mGridColor = gdef.colors[COLOR_MGRID];
		Paint fontColor = gdef.colors[COLOR_FONT];
		int fontHeight = (int) Math.ceil(worker.getFontHeight(font));
		int labelOffset = (int) (worker.getFontAscent(font) / 2);

		double pixpex = (double) im.ysize / (Math.log10(im.maxval) - Math.log10(im.minval));
		if (Double.isNaN(pixpex)) {
			return false;
		}
		double minstep, pixperstep;
		int minoridx = 0, majoridx = 0;
		for (int i = 0; yloglab[i][0] > 0; i++) {
			minstep = Math.log10(yloglab[i][0]);
			for (int ii = 1; yloglab[i][ii + 1] > 0; ii++) {
				if (yloglab[i][ii + 2] == 0) {
					minstep = Math.log10(yloglab[i][ii + 1]) - Math.log10(yloglab[i][ii]);
					break;
				}
			}
			pixperstep = pixpex * minstep;
			if (pixperstep > 5) {
				minoridx = i;
			}
			if (pixperstep > 2 * fontHeight) {
				majoridx = i;
			}
		}
		int x0 = im.xorigin, x1 = x0 + im.xsize;
		for (double value = Math.pow(10, Math.log10(im.minval)
				- Math.log10(im.minval) % Math.log10(yloglab[minoridx][0]));
			 value <= im.maxval;
			 value *= yloglab[minoridx][0]) {
			if (value < im.minval) {
				continue;
			}
			int i = 0;
			while (yloglab[minoridx][++i] > 0) {
				int y = rrdGraph.mapper.ytr(value * yloglab[minoridx][i]);
				if (y <= im.yorigin - im.ysize) {
					break;
				}
				worker.drawLine(x0 - 1, y, x0 + 1, y, gridColor, TICK_STROKE);
				worker.drawLine(x1 - 1, y, x1 + 1, y, gridColor, TICK_STROKE);
				worker.drawLine(x0, y, x1, y, gridColor, GRID_STROKE);
			}
		}
		for (double value = Math.pow(10, Math.log10(im.minval)
				- (Math.log10(im.minval) % Math.log10(yloglab[majoridx][0])));
			 value <= im.maxval;
			 value *= yloglab[majoridx][0]) {
			if (value < im.minval) {
				continue;
			}
			int i = 0;
			while (yloglab[majoridx][++i] > 0) {
				int y = rrdGraph.mapper.ytr(value * yloglab[majoridx][i]);
				if (y <= im.yorigin - im.ysize) {
					break;
				}
				worker.drawLine(x0 - 2, y, x0 + 2, y, mGridColor, TICK_STROKE);
				worker.drawLine(x1 - 2, y, x1 + 2, y, mGridColor, TICK_STROKE);
				worker.drawLine(x0, y, x1, y, mGridColor, GRID_STROKE);
				String graph_label = Util.sprintf("%3.0e", value * yloglab[majoridx][i]);
				int length = (int) (worker.getStringWidth(graph_label, font));
				worker.drawString(graph_label, x0 - length - PADDING_VLABEL, y + labelOffset, font, fontColor);
			}
		}
		return true;
	}
}
