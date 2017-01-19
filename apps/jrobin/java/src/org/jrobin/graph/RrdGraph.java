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

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

/**
 * Class which actually creates JRobin graphs (does the hard work).
 */
public class RrdGraph implements RrdGraphConstants {
	RrdGraphDef gdef;
	ImageParameters im = new ImageParameters();
	DataProcessor dproc;
	ImageWorker worker;
	Mapper mapper;
	RrdGraphInfo info = new RrdGraphInfo();
	private String signature;

	/**
	 * Creates graph from the corresponding {@link RrdGraphDef} object.
	 *
	 * @param gdef Graph definition
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin related error
	 */
	public RrdGraph(RrdGraphDef gdef) throws IOException, RrdException {
		this.gdef = gdef;
		signature = gdef.getSignature();
		worker = new ImageWorker(100, 100); // Dummy worker, just to start with something
		try {
			createGraph();
		}
		finally {
			worker.dispose();
			worker = null;
			dproc = null;
		}
	}

	/**
	 * Returns complete graph information in a single object.
	 *
	 * @return Graph information (width, height, filename, image bytes, etc...)
	 */
	public RrdGraphInfo getRrdGraphInfo() {
		return info;
	}

	private void createGraph() throws RrdException, IOException {
		boolean lazy = lazyCheck();
		if (!lazy || gdef.printStatementCount() != 0) {
			fetchData();
			resolveTextElements();
			if (gdef.shouldPlot() && !lazy) {
				calculatePlotValues();
				findMinMaxValues();
				identifySiUnit();
				expandValueRange();
				removeOutOfRangeRules();
				initializeLimits();
				placeLegends();
				createImageWorker();
				drawBackground();
				drawData();
				drawGrid();
				drawAxis();
				drawText();
				drawLegend();
				drawRules();
				gator();
				drawOverlay();
				saveImage();
			}
		}
		collectInfo();
	}

	private void collectInfo() {
		info.filename = gdef.filename;
		info.width = im.xgif;
		info.height = im.ygif;
		for (CommentText comment : gdef.comments) {
			if (comment instanceof PrintText) {
				PrintText pt = (PrintText) comment;
				if (pt.isPrint()) {
					info.addPrintLine(pt.resolvedText);
				}
			}
		}
		if (gdef.imageInfo != null) {
			info.imgInfo = Util.sprintf(gdef.imageInfo, gdef.filename, im.xgif, im.ygif);
		}
	}

	private void saveImage() throws IOException {
		if (!gdef.filename.equals("-")) {
			info.bytes = worker.saveImage(gdef.filename, gdef.imageFormat, gdef.imageQuality);
		}
		else {
			info.bytes = worker.getImageBytes(gdef.imageFormat, gdef.imageQuality);
		}
	}

	private void drawOverlay() throws IOException {
		if (gdef.overlayImage != null) {
			worker.loadImage(gdef.overlayImage);
		}
	}

	private void gator() {
		if (!gdef.onlyGraph && gdef.showSignature) {
			Font font = gdef.getFont(FONTTAG_WATERMARK);
			int x = (int) (im.xgif - 2 - worker.getFontAscent(font));
			int y = 4;
			worker.transform(x, y, Math.PI / 2);
			worker.drawString(signature, 0, 0, font, Color.LIGHT_GRAY);
			worker.reset();
		}
	}

	private void drawRules() {
		worker.clip(im.xorigin + 1, im.yorigin - gdef.height - 1, gdef.width - 1, gdef.height + 2);
		for (PlotElement pe : gdef.plotElements) {
			if (pe instanceof HRule) {
				HRule hr = (HRule) pe;
				if (hr.value >= im.minval && hr.value <= im.maxval) {
					int y = mapper.ytr(hr.value);
					worker.drawLine(im.xorigin, y, im.xorigin + im.xsize, y, hr.color, new BasicStroke(hr.width));
				}
			}
			else if (pe instanceof VRule) {
				VRule vr = (VRule) pe;
				if (vr.timestamp >= im.start && vr.timestamp <= im.end) {
					int x = mapper.xtr(vr.timestamp);
					worker.drawLine(x, im.yorigin, x, im.yorigin - im.ysize, vr.color, new BasicStroke(vr.width));
				}
			}
		}
		worker.reset();
	}

	private void drawText() {
		if (!gdef.onlyGraph) {
			if (gdef.title != null) {
				int x = im.xgif / 2 - (int) (worker.getStringWidth(gdef.title, gdef.getFont(FONTTAG_TITLE)) / 2);
				int y = PADDING_TOP + (int) worker.getFontAscent(gdef.getFont(FONTTAG_TITLE));
				worker.drawString(gdef.title, x, y, gdef.getFont(FONTTAG_TITLE), gdef.colors[COLOR_FONT]);
			}
			if (gdef.verticalLabel != null) {
				int x = PADDING_LEFT;
				int y = im.yorigin - im.ysize / 2 + (int) worker.getStringWidth(gdef.verticalLabel, gdef.getFont(FONTTAG_UNIT)) / 2;
				int ascent = (int) worker.getFontAscent(gdef.getFont(FONTTAG_UNIT));
				worker.transform(x, y, -Math.PI / 2);
				worker.drawString(gdef.verticalLabel, 0, ascent, gdef.getFont(FONTTAG_UNIT), gdef.colors[COLOR_FONT]);
				worker.reset();
			}
		}
	}

	private void drawGrid() {
		if (!gdef.onlyGraph) {
			Paint shade1 = gdef.colors[COLOR_SHADEA], shade2 = gdef.colors[COLOR_SHADEB];
			Stroke borderStroke = new BasicStroke(1);
			worker.drawLine(0, 0, im.xgif - 1, 0, shade1, borderStroke);
			worker.drawLine(1, 1, im.xgif - 2, 1, shade1, borderStroke);
			worker.drawLine(0, 0, 0, im.ygif - 1, shade1, borderStroke);
			worker.drawLine(1, 1, 1, im.ygif - 2, shade1, borderStroke);
			worker.drawLine(im.xgif - 1, 0, im.xgif - 1, im.ygif - 1, shade2, borderStroke);
			worker.drawLine(0, im.ygif - 1, im.xgif - 1, im.ygif - 1, shade2, borderStroke);
			worker.drawLine(im.xgif - 2, 1, im.xgif - 2, im.ygif - 2, shade2, borderStroke);
			worker.drawLine(1, im.ygif - 2, im.xgif - 2, im.ygif - 2, shade2, borderStroke);
			if (gdef.drawXGrid) {
				new TimeAxis(this).draw();
			}
			if (gdef.drawYGrid) {
				boolean ok;
				if (gdef.altYMrtg) {
					ok = new ValueAxisMrtg(this).draw();
				}
				else if (gdef.logarithmic) {
					ok = new ValueAxisLogarithmic(this).draw();
				}
				else {
					ok = new ValueAxis(this).draw();
				}
				if (!ok) {
					String msg = "No Data Found";
					worker.drawString(msg,
							im.xgif / 2 - (int) worker.getStringWidth(msg, gdef.getFont(FONTTAG_TITLE)) / 2,
							(2 * im.yorigin - im.ysize) / 2,
							gdef.getFont(FONTTAG_TITLE), gdef.colors[COLOR_FONT]);
				}
			}
		}
	}

	private void drawData() throws RrdException {
		worker.setAntiAliasing(gdef.antiAliasing);
		worker.clip(im.xorigin + 1, im.yorigin - gdef.height - 1, gdef.width - 1, gdef.height + 2);
		double areazero = mapper.ytr((im.minval > 0.0) ? im.minval : (im.maxval < 0.0) ? im.maxval : 0.0);
		double[] x = xtr(dproc.getTimestamps()), lastY = null;
		// draw line, area and stack
		for (PlotElement plotElement : gdef.plotElements) {
			if (plotElement instanceof SourcedPlotElement) {
				SourcedPlotElement source = (SourcedPlotElement) plotElement;
				double[] y = ytr(source.getValues());
				if (source instanceof Line) {
					worker.drawPolyline(x, y, source.color, new BasicStroke(((Line) source).width));
				}
				else if (source instanceof Area) {
					worker.fillPolygon(x, areazero, y, source.color);
				}
				else if (source instanceof Stack) {
					Stack stack = (Stack) source;
					float width = stack.getParentLineWidth();
					if (width >= 0F) {
						// line
						worker.drawPolyline(x, y, stack.color, new BasicStroke(width));
					}
					else {
						// area
						worker.fillPolygon(x, lastY, y, stack.color);
						worker.drawPolyline(x, lastY, stack.getParentColor(), new BasicStroke(0));
					}
				}
				else {
					// should not be here
					throw new RrdException("Unknown plot source: " + source.getClass().getName());
				}
				lastY = y;
			}
		}
		worker.reset();
		worker.setAntiAliasing(false);
	}

	private void drawAxis() {
		if (!gdef.onlyGraph) {
			Paint gridColor = gdef.colors[COLOR_GRID];
			Paint fontColor = gdef.colors[COLOR_FONT];
			Paint arrowColor = gdef.colors[COLOR_ARROW];
			Stroke stroke = new BasicStroke(1);
			worker.drawLine(im.xorigin + im.xsize, im.yorigin, im.xorigin + im.xsize, im.yorigin - im.ysize,
					gridColor, stroke);
			worker.drawLine(im.xorigin, im.yorigin - im.ysize, im.xorigin + im.xsize, im.yorigin - im.ysize,
					gridColor, stroke);
			worker.drawLine(im.xorigin - 4, im.yorigin, im.xorigin + im.xsize + 4, im.yorigin,
					fontColor, stroke);
			worker.drawLine(im.xorigin, im.yorigin, im.xorigin, im.yorigin - im.ysize,
					gridColor, stroke);
			worker.drawLine(im.xorigin + im.xsize + 4, im.yorigin - 3, im.xorigin + im.xsize + 4, im.yorigin + 3,
					arrowColor, stroke);
			worker.drawLine(im.xorigin + im.xsize + 4, im.yorigin - 3, im.xorigin + im.xsize + 9, im.yorigin,
					arrowColor, stroke);
			worker.drawLine(im.xorigin + im.xsize + 4, im.yorigin + 3, im.xorigin + im.xsize + 9, im.yorigin,
					arrowColor, stroke);
		}
	}

	private void drawBackground() throws IOException {
		worker.fillRect(0, 0, im.xgif, im.ygif, gdef.colors[COLOR_BACK]);
		if (gdef.backgroundImage != null) {
			worker.loadImage(gdef.backgroundImage);
		}
		worker.fillRect(im.xorigin, im.yorigin - im.ysize, im.xsize, im.ysize, gdef.colors[COLOR_CANVAS]);
	}

	private void createImageWorker() {
		worker.resize(im.xgif, im.ygif);
	}

	private void placeLegends() {
		if (!gdef.noLegend && !gdef.onlyGraph) {
			int border = (int) (getSmallFontCharWidth() * PADDING_LEGEND);
			LegendComposer lc = new LegendComposer(this, border, im.ygif, im.xgif - 2 * border);
			im.ygif = lc.placeComments() + PADDING_BOTTOM;
		}
	}

	private void initializeLimits() throws RrdException {
		im.xsize = gdef.width;
		im.ysize = gdef.height;
		im.unitslength = gdef.unitsLength;
		if (gdef.onlyGraph) {
			if (im.ysize > 64) {
				throw new RrdException("Cannot create graph only, height too big");
			}
			im.xorigin = 0;
		}
		else {
			im.xorigin = (int) (PADDING_LEFT + im.unitslength * getSmallFontCharWidth());
		}
		if (gdef.verticalLabel != null) {
			im.xorigin += getFontHeight(FONTTAG_UNIT);
		}
		if (gdef.onlyGraph) {
			im.yorigin = im.ysize;
		}
		else {
			im.yorigin = PADDING_TOP + im.ysize;
		}
		mapper = new Mapper(this);
		if (gdef.title != null) {
			im.yorigin += getFontHeight(FONTTAG_TITLE) + PADDING_TITLE;
		}
		if (gdef.onlyGraph) {
			im.xgif = im.xsize;
			im.ygif = im.yorigin;
		}
		else {
			im.xgif = PADDING_RIGHT + im.xsize + im.xorigin;
			im.ygif = im.yorigin + (int) (PADDING_PLOT * getFontHeight(FONTTAG_DEFAULT));
		}
	}

	private void removeOutOfRangeRules() {
		for (PlotElement plotElement : gdef.plotElements) {
			if (plotElement instanceof HRule) {
				((HRule) plotElement).setLegendVisibility(im.minval, im.maxval, gdef.forceRulesLegend);
			}
			else if (plotElement instanceof VRule) {
				((VRule) plotElement).setLegendVisibility(im.start, im.end, gdef.forceRulesLegend);
			}
		}
	}

	private void expandValueRange() {
		im.ygridstep = (gdef.valueAxisSetting != null) ? gdef.valueAxisSetting.gridStep : Double.NaN;
		im.ylabfact = (gdef.valueAxisSetting != null) ? gdef.valueAxisSetting.labelFactor : 0;
		if (!gdef.rigid && !gdef.logarithmic) {
			double sensiblevalues[] = {
					1000.0, 900.0, 800.0, 750.0, 700.0, 600.0, 500.0, 400.0, 300.0, 250.0, 200.0, 125.0, 100.0,
					90.0, 80.0, 75.0, 70.0, 60.0, 50.0, 40.0, 30.0, 25.0, 20.0, 10.0,
					9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.5, 3.0, 2.5, 2.0, 1.8, 1.5, 1.2, 1.0,
					0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 0.0, -1
			};
			double scaled_min, scaled_max, adj;
			if (Double.isNaN(im.ygridstep)) {
				if (gdef.altYMrtg) { /* mrtg */
					im.decimals = Math.ceil(Math.log10(Math.max(Math.abs(im.maxval), Math.abs(im.minval))));
					im.quadrant = 0;
					if (im.minval < 0) {
						im.quadrant = 2;
						if (im.maxval <= 0) {
							im.quadrant = 4;
						}
					}
					switch (im.quadrant) {
						case 2:
							im.scaledstep = Math.ceil(50 * Math.pow(10, -(im.decimals)) * Math.max(Math.abs(im.maxval),
									Math.abs(im.minval))) * Math.pow(10, im.decimals - 2);
							scaled_min = -2 * im.scaledstep;
							scaled_max = 2 * im.scaledstep;
							break;
						case 4:
							im.scaledstep = Math.ceil(25 * Math.pow(10,
									-(im.decimals)) * Math.abs(im.minval)) * Math.pow(10, im.decimals - 2);
							scaled_min = -4 * im.scaledstep;
							scaled_max = 0;
							break;
						default: /* quadrant 0 */
							im.scaledstep = Math.ceil(25 * Math.pow(10, -(im.decimals)) * im.maxval) *
									Math.pow(10, im.decimals - 2);
							scaled_min = 0;
							scaled_max = 4 * im.scaledstep;
							break;
					}
					im.minval = scaled_min;
					im.maxval = scaled_max;
				}
				else if (gdef.altAutoscale) {
					/* measure the amplitude of the function. Make sure that
					   graph boundaries are slightly higher then max/min vals
					   so we can see amplitude on the graph */
					double delt, fact;

					delt = im.maxval - im.minval;
					adj = delt * 0.1;
					fact = 2.0 * Math.pow(10.0,
							Math.floor(Math.log10(Math.max(Math.abs(im.minval), Math.abs(im.maxval)))) - 2);
					if (delt < fact) {
						adj = (fact - delt) * 0.55;
					}
					im.minval -= adj;
					im.maxval += adj;
				}
				else if (gdef.altAutoscaleMax) {
					/* measure the amplitude of the function. Make sure that
					   graph boundaries are slightly higher than max vals
					   so we can see amplitude on the graph */
					adj = (im.maxval - im.minval) * 0.1;
					im.maxval += adj;
				}
				else {
					scaled_min = im.minval / im.magfact;
					scaled_max = im.maxval / im.magfact;
					for (int i = 1; sensiblevalues[i] > 0; i++) {
						if (sensiblevalues[i - 1] >= scaled_min && sensiblevalues[i] <= scaled_min) {
							im.minval = sensiblevalues[i] * im.magfact;
						}
						if (-sensiblevalues[i - 1] <= scaled_min && -sensiblevalues[i] >= scaled_min) {
							im.minval = -sensiblevalues[i - 1] * im.magfact;
						}
						if (sensiblevalues[i - 1] >= scaled_max && sensiblevalues[i] <= scaled_max) {
							im.maxval = sensiblevalues[i - 1] * im.magfact;
						}
						if (-sensiblevalues[i - 1] <= scaled_max && -sensiblevalues[i] >= scaled_max) {
							im.maxval = -sensiblevalues[i] * im.magfact;
						}
					}
				}
			}
			else {
				im.minval = (double) im.ylabfact * im.ygridstep *
						Math.floor(im.minval / ((double) im.ylabfact * im.ygridstep));
				im.maxval = (double) im.ylabfact * im.ygridstep *
						Math.ceil(im.maxval / ((double) im.ylabfact * im.ygridstep));
			}

		}
	}

	private void identifySiUnit() {
		im.unitsexponent = gdef.unitsExponent;
		im.base = gdef.base;
		if (!gdef.logarithmic) {
			final char symbol[] = {'a', 'f', 'p', 'n', 'u', 'm', ' ', 'k', 'M', 'G', 'T', 'P', 'E'};
			int symbcenter = 6;
			double digits;
			if (im.unitsexponent != Integer.MAX_VALUE) {
				digits = Math.floor(im.unitsexponent / 3.0);
			}
			else {
				digits = Math.floor(Math.log(Math.max(Math.abs(im.minval), Math.abs(im.maxval))) / Math.log(im.base));
			}
			im.magfact = Math.pow(im.base, digits);
			if (((digits + symbcenter) < symbol.length) && ((digits + symbcenter) >= 0)) {
				im.symbol = symbol[(int) digits + symbcenter];
			}
			else {
				im.symbol = '?';
			}
		}
	}

	private void findMinMaxValues() {
		double minval = Double.NaN, maxval = Double.NaN;
		for (PlotElement pe : gdef.plotElements) {
			if (pe instanceof SourcedPlotElement) {
				minval = Util.min(((SourcedPlotElement) pe).getMinValue(), minval);
				maxval = Util.max(((SourcedPlotElement) pe).getMaxValue(), maxval);
			}
		}
		if (Double.isNaN(minval)) {
			minval = 0D;
		}
		if (Double.isNaN(maxval)) {
			maxval = 1D;
		}
		im.minval = gdef.minValue;
		im.maxval = gdef.maxValue;
		/* adjust min and max values */
		if (Double.isNaN(im.minval) || ((!gdef.logarithmic && !gdef.rigid) && im.minval > minval)) {
			im.minval = minval;
		}
		if (Double.isNaN(im.maxval) || (!gdef.rigid && im.maxval < maxval)) {
			if (gdef.logarithmic) {
				im.maxval = maxval * 1.1;
			}
			else {
				im.maxval = maxval;
			}
		}
		/* make sure min is smaller than max */
		if (im.minval > im.maxval) {
			im.minval = 0.99 * im.maxval;
		}
		/* make sure min and max are not equal */
		if (Math.abs(im.minval - im.maxval) < .0000001) {
			im.maxval *= 1.01;
			if (!gdef.logarithmic) {
				im.minval *= 0.99;
			}
			/* make sure min and max are not both zero */
			if (im.maxval == 0.0) {
				im.maxval = 1.0;
			}
		}
	}

	private void calculatePlotValues() throws RrdException {
		for (PlotElement pe : gdef.plotElements) {
			if (pe instanceof SourcedPlotElement) {
				((SourcedPlotElement) pe).assignValues(dproc);
			}
		}
	}

	private void resolveTextElements() throws RrdException {
		ValueScaler valueScaler = new ValueScaler(gdef.base);
		for (CommentText comment : gdef.comments) {
			comment.resolveText(dproc, valueScaler);
		}
	}

	private void fetchData() throws RrdException, IOException {
		dproc = new DataProcessor(gdef.startTime, gdef.endTime);
		dproc.setPoolUsed(gdef.poolUsed);
		if (gdef.step > 0) {
			dproc.setStep(gdef.step);
		}
		for (Source src : gdef.sources) {
			src.requestData(dproc);
		}
		dproc.processData();
		//long[] t = dproc.getTimestamps();
		//im.start = t[0];
		//im.end = t[t.length - 1];
		im.start = gdef.startTime;
		im.end = gdef.endTime;
	}

	private boolean lazyCheck() {
		// redraw if lazy option is not set or file does not exist
		if (!gdef.lazy || !Util.fileExists(gdef.filename)) {
			return false; // 'false' means 'redraw'
		}
		// redraw if not enough time has passed
		long secPerPixel = (gdef.endTime - gdef.startTime) / gdef.width;
		long elapsed = Util.getTimestamp() - Util.getLastModified(gdef.filename);
		return elapsed <= secPerPixel;
	}

	private void drawLegend() {
		if (!gdef.onlyGraph && !gdef.noLegend) {
			int ascent = (int) worker.getFontAscent(gdef.getFont(FONTTAG_LEGEND));
			int box = (int) getBox(), boxSpace = (int) (getBoxSpace());
			for (CommentText c : gdef.comments) {
				if (c.isValidGraphElement()) {
					int x = c.x, y = c.y + ascent;
					if (c instanceof LegendText) {
						// draw with BOX
						worker.fillRect(x, y - box, box, box, gdef.colors[COLOR_FRAME]);
						worker.fillRect(x + 1, y - box + 1, box - 2, box - 2, gdef.colors[COLOR_CANVAS]);
						worker.fillRect(x + 1, y - box + 1, box - 2, box - 2, ((LegendText) c).legendColor);
						worker.drawString(c.resolvedText, x + boxSpace, y, gdef.getFont(FONTTAG_LEGEND), gdef.colors[COLOR_FONT]);
					}
					else {
						worker.drawString(c.resolvedText, x, y, gdef.getFont(FONTTAG_LEGEND), gdef.colors[COLOR_FONT]);
					}
				}
			}
		}
	}

	// helper methods

	double getFontHeight(int fonttag) {
		return worker.getFontHeight(gdef.getFont(fonttag));
	}

	double getFontCharWidth(int fonttag) {
		return worker.getStringWidth("a", gdef.getFont(fonttag));
	}

	double getSmallFontHeight() {
		return getFontHeight(FONTTAG_LEGEND);
	}

	@SuppressWarnings("unused")
	private double getTitleFontHeight() {
		return getFontHeight(FONTTAG_TITLE);
	}

	private double getSmallFontCharWidth() {
		return getFontCharWidth(FONTTAG_LEGEND);
	}

	double getInterlegendSpace() {
		return getFontCharWidth(FONTTAG_LEGEND) * LEGEND_INTERSPACING;
	}

	double getLeading() {
		return getFontHeight(FONTTAG_LEGEND) * LEGEND_LEADING;
	}

	double getSmallLeading() {
		return getFontHeight(FONTTAG_LEGEND) * LEGEND_LEADING_SMALL;
	}

	double getBoxSpace() {
		return Math.ceil(getFontHeight(FONTTAG_LEGEND) * LEGEND_BOX_SPACE);
	}

	private double getBox() {
		return getFontHeight(FONTTAG_LEGEND) * LEGEND_BOX;
	}

	double[] xtr(long[] timestamps) {
		/*
		double[] timestampsDev = new double[timestamps.length];
		for (int i = 0; i < timestamps.length; i++) {
			timestampsDev[i] = mapper.xtr(timestamps[i]);
		}
		return timestampsDev;
		*/
		double[] timestampsDev = new double[2 * timestamps.length - 1];
		for (int i = 0, j = 0; i < timestamps.length; i += 1, j += 2) {
			timestampsDev[j] = mapper.xtr(timestamps[i]);
			if (i < timestamps.length - 1) {
				timestampsDev[j + 1] = timestampsDev[j];
			}
		}
		return timestampsDev;
	}

	double[] ytr(double[] values) {
		/*
		double[] valuesDev = new double[values.length];
		for (int i = 0; i < values.length; i++) {
			if (Double.isNaN(values[i])) {
				valuesDev[i] = Double.NaN;
			}
			else {
				valuesDev[i] = mapper.ytr(values[i]);
			}
		}
		return valuesDev;
		*/
		double[] valuesDev = new double[2 * values.length - 1];
		for (int i = 0, j = 0; i < values.length; i += 1, j += 2) {
			if (Double.isNaN(values[i])) {
				valuesDev[j] = Double.NaN;
			}
			else {
				valuesDev[j] = mapper.ytr(values[i]);
			}
			if (j > 0) {
				valuesDev[j - 1] = valuesDev[j];
			}
		}
		return valuesDev;
	}

	/**
	 * Renders this graph onto graphing device
	 *
	 * @param g Graphics handle
	 */
	public void render(Graphics g) {
		byte[] imageData = getRrdGraphInfo().getBytes();
		ImageIcon image = new ImageIcon(imageData);
		image.paintIcon(null, g, 0, 0);
	}
}
