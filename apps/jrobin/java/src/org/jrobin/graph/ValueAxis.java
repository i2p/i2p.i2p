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

import java.awt.Font;
import java.awt.Paint;

import org.jrobin.core.Util;

class ValueAxis implements RrdGraphConstants {
	private static final YLab[] ylab = {
		new YLab(0.1, 1, 2, 5, 10),
		new YLab(0.2, 1, 5, 10, 20),
		new YLab(0.5, 1, 2, 4, 10),
		new YLab(1.0, 1, 2, 5, 10),
		new YLab(2.0, 1, 5, 10, 20),
		new YLab(5.0, 1, 2, 4, 10),
		new YLab(10.0, 1, 2, 5, 10),
		new YLab(20.0, 1, 5, 10, 20),
		new YLab(50.0, 1, 2, 4, 10),
		new YLab(100.0, 1, 2, 5, 10),
		new YLab(200.0, 1, 5, 10, 20),
		new YLab(500.0, 1, 2, 4, 10),
		new YLab(1000.0, 1, 2, 5, 10),
		new YLab(2000.0, 1, 5, 10, 20),
		new YLab(5000.0, 1, 2, 4, 10),
		new YLab(10000.0, 1, 2, 5, 10),
		new YLab(20000.0, 1, 5, 10, 20),
		new YLab(50000.0, 1, 2, 4, 10),
		new YLab(100000.0, 1, 2, 5, 10),
		new YLab(0.0, 0, 0, 0, 0)
	};

	//private RrdGraph rrdGraph;
	private ImageParameters im;
	private ImageWorker worker;
	private RrdGraphDef gdef;
	private Mapper mapper;

	ValueAxis(RrdGraph rrdGraph) {
		this(rrdGraph.im, rrdGraph.worker, rrdGraph.gdef, rrdGraph.mapper);
	}

	ValueAxis(ImageParameters im, ImageWorker worker, RrdGraphDef gdef, Mapper mapper) {
		this.im = im;
		this.gdef = gdef;
		this.worker = worker;
		this.mapper = mapper;
	}

	boolean draw() {
		Font font = gdef.getFont(FONTTAG_AXIS);
		Paint gridColor = gdef.colors[COLOR_GRID];
		Paint mGridColor = gdef.colors[COLOR_MGRID];
		Paint fontColor = gdef.colors[COLOR_FONT];
		int labelOffset = (int) (worker.getFontAscent(font) / 2);
		int labfact = 2;
		double range = im.maxval - im.minval;
		double scaledrange = range / im.magfact;
		double gridstep;
		if (Double.isNaN(scaledrange)) {
			return false;
		}
		String labfmt = null;
		if (Double.isNaN(im.ygridstep)) {
			if (gdef.altYGrid) {
				/* find the value with max number of digits. Get number of digits */
				int decimals = (int) Math.ceil(Math.log10(Math.max(Math.abs(im.maxval),
						Math.abs(im.minval))));
				if (decimals <= 0) /* everything is small. make place for zero */ {
					decimals = 1;
				}
				int fractionals = (int) Math.floor(Math.log10(range));
				if (fractionals < 0) /* small amplitude. */ {
					labfmt = Util.sprintf("%%%d.%df", decimals - fractionals + 1, -fractionals + 1);
				}
				else {
					labfmt = Util.sprintf("%%%d.1f", decimals + 1);
				}
				gridstep = Math.pow(10, fractionals);
				if (gridstep == 0) /* range is one -> 0.1 is reasonable scale */ {
					gridstep = 0.1;
				}
				/* should have at least 5 lines but no more then 15 */
				if (range / gridstep < 5) {
					gridstep /= 10;
				}
				if (range / gridstep > 15) {
					gridstep *= 10;
				}
				if (range / gridstep > 5) {
					labfact = 1;
					if (range / gridstep > 8) {
						labfact = 2;
					}
				}
				else {
					gridstep /= 5;
					labfact = 5;
				}
			}
			else {
				//Start looking for a minimum of 3 labels, but settle for 2 or 1 if need be
				int minimumLabelCount = 3;
				YLab selectedYLab = null;
				while(selectedYLab == null) {
					selectedYLab = findYLab(minimumLabelCount);
					minimumLabelCount--;
				}
				gridstep = selectedYLab.grid * im.magfact;
				labfact = findLabelFactor(selectedYLab);
			}
		}
		else {
			gridstep = im.ygridstep;
			labfact = im.ylabfact;
		}
		int x0 = im.xorigin, x1 = x0 + im.xsize;
		int sgrid = (int) (im.minval / gridstep - 1);
		int egrid = (int) (im.maxval / gridstep + 1);
		double scaledstep = gridstep / im.magfact;
		for (int i = sgrid; i <= egrid; i++) {
			int y = this.mapper.ytr(gridstep * i);
			if (y >= im.yorigin - im.ysize && y <= im.yorigin) {
				if (i % labfact == 0) {
					String graph_label;
					if (i == 0 || im.symbol == ' ') {
						if (scaledstep < 1) {
							if (i != 0 && gdef.altYGrid) {
								graph_label = Util.sprintf(labfmt, scaledstep * i);
							}
							else {
								graph_label = Util.sprintf("%4.1f", scaledstep * i);
							}
						}
						else {
							graph_label = Util.sprintf("%4.0f", scaledstep * i);
						}
					}
					else {
						if (scaledstep < 1) {
							graph_label = Util.sprintf("%4.1f %c", scaledstep * i, im.symbol);
						}
						else {
							graph_label = Util.sprintf("%4.0f %c", scaledstep * i, im.symbol);
						}
					}
					int length = (int) (worker.getStringWidth(graph_label, font));
					worker.drawString(graph_label, x0 - length - PADDING_VLABEL, y + labelOffset, font, fontColor);
					worker.drawLine(x0 - 2, y, x0 + 2, y, mGridColor, TICK_STROKE);
					worker.drawLine(x1 - 2, y, x1 + 2, y, mGridColor, TICK_STROKE);
					worker.drawLine(x0, y, x1, y, mGridColor, GRID_STROKE);
				}
				else if (!(gdef.noMinorGrid)) {
					worker.drawLine(x0 - 1, y, x0 + 1, y, gridColor, TICK_STROKE);
					worker.drawLine(x1 - 1, y, x1 + 1, y, gridColor, TICK_STROKE);
					worker.drawLine(x0, y, x1, y, gridColor, GRID_STROKE);
				}
			}
		}
		return true;
	}

/**
* Finds an acceptable YLab object for the current graph
* If the graph covers positive and negative on the y-axis, then
* desiredMinimumLabelCount is checked as well, to ensure the chosen YLab definition
        * will result in the required number of labels
*
	* Returns null if none are acceptable (none the right size or with
* enough labels)
*/
private YLab findYLab(int desiredMinimumLabelCount) {
	double scaledrange = this.getScaledRange();
	int labelFactor;
	//Check each YLab definition to see if it's acceptable
	for (int i = 0; ylab[i].grid > 0; i++) {
		YLab thisYLab = ylab[i];
		//First cut is whether this gridstep would give enough space per gridline
		if (this.getPixelsPerGridline(thisYLab) > 5 ) {
			//Yep; now we might have to check the number of labels
			if(im.minval < 0.0 && im.maxval > 0.0) {
				//The graph covers positive and negative values, so we need the
				// desiredMinimumLabelCount number of labels, which is going to
				// usually be 3, then maybe 2, then only as a last resort, 1.
				// So, we need to find out what the label factor would be
				// if we chose this ylab definition
				labelFactor = findLabelFactor(thisYLab);
				if(labelFactor == -1) {
					//Default to too many to satisfy the label count test, unless we're looking for just 1
					// in which case be sure to satisfy the label count test
					labelFactor = desiredMinimumLabelCount==1?1:desiredMinimumLabelCount+1;
				}
				//Adding one?  Think fenceposts (need one more than just dividing length by space between)
				int labelCount = ((int)(scaledrange/thisYLab.grid)/labelFactor)+1;
				if(labelCount > desiredMinimumLabelCount) {
					return thisYLab; //Enough pixels, *and* enough labels
				}

			} else {
				//Only positive or negative on the graph y-axis.  No need to
				// care about the label count.
				return thisYLab;
			}
		}
	}

	double val = 1;
	while(val < scaledrange) {
	    val = val * 10;
	}
	return new YLab(val/10, 1, 2, 5, 10);
}

/**
	 * Find the smallest labelFactor acceptable (can fit labels) for the given YLab definition
 * Returns the label factor if one is ok, otherwise returns -1 if none are acceptable
 */
private int findLabelFactor(YLab thisYLab) {
	int pixel = this.getPixelsPerGridline(thisYLab);
	int fontHeight = (int) Math.ceil(worker.getFontHeight(gdef.getFont(FONTTAG_AXIS)));
	for (int j = 0; j < 4; j++) {
		if (pixel * thisYLab.lfac[j] >= 2 * fontHeight) {
			return thisYLab.lfac[j];
		}
	}
	return -1;
}

/**
 * Finds the number of pixels per gridline that the given YLab definition will result in
 */
private int getPixelsPerGridline(YLab thisYLab) {
	double scaledrange = this.getScaledRange();
	return (int) (im.ysize / (scaledrange / thisYLab.grid));
}

private double getScaledRange() {
	double range = im.maxval - im.minval;
	return range / im.magfact;
}


	static class YLab {
		double grid;
		int[] lfac;

		YLab(double grid, int lfac1, int lfac2, int lfac3, int lfac4) {
			this.grid = grid;
			lfac = new int[] {lfac1, lfac2, lfac3, lfac4};
		}
	}
}
