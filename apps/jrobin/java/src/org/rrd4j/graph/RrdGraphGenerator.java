package org.rrd4j.graph;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.rrd4j.core.Util;
import org.rrd4j.data.DataProcessor;

class RrdGraphGenerator {

    private static final double[] SENSIBLE_VALUES = {
            1000.0, 900.0, 800.0, 750.0, 700.0,
            600.0, 500.0, 400.0, 300.0, 250.0,
            200.0, 125.0, 100.0, 90.0, 80.0,
            75.0, 70.0, 60.0, 50.0, 40.0, 30.0,
            25.0, 20.0, 10.0, 9.0, 8.0,
            7.0, 6.0, 5.0, 4.0, 3.5, 3.0,
            2.5, 2.0, 1.8, 1.5, 1.2, 1.0,
            0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 0.0, -1
    };
    private static final int SYMBOLS_CENTER = 8;
    private static final char[] SYMBOLS = {'y', 'z', 'a', 'f', 'p', 'n', 'µ', 'm', ' ', 'K', 'M', 'G', 'T', 'P', 'E', 'Z', 'Y'};

    final RrdGraphDef gdef;
    final ImageWorker worker;
    private final DataProcessor dproc;
    Mapper mapper;
    final RrdGraphInfo info = new RrdGraphInfo();
    private final String signature;
    final ImageParameters im;

    RrdGraphGenerator(RrdGraphDef gdef, ImageWorker worker, DataProcessor dproc) {
        this.gdef = gdef;
        this.worker = worker;
        this.dproc = dproc;
        signature = gdef.getSignature();
        im = new ImageParameters();
    }

    void createGraph() throws IOException {
        boolean lazy = lazyCheck();
        if (!lazy || gdef.printStatementCount() != 0) {
            fetchData();
            resolveTextElements();
            if (gdef.shouldPlot() && !lazy) {
                initializeLimits();
                calculatePlotValues();
                findMinMaxValues();
                identifySiUnit();
                expandValueRange();
                removeOutOfRangeRules();
                removeOutOfRangeSpans();
                mapper = new Mapper(gdef, im);
                placeLegends();
                createImageWorker();
                drawBackground();
                drawData();
                drawGrid();
                drawAxis();
                drawText();
                drawLegend();
                drawRulesAndSpans();
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
            info.imgInfo = Util.sprintf(gdef.locale, gdef.imageInfo, gdef.filename, im.xgif, im.ygif);
        }
    }

    private void saveImage() throws IOException {
        if (!RrdGraphConstants.IN_MEMORY_IMAGE.equals(gdef.filename)) {
            Path imgpath = Paths.get(gdef.filename);
            worker.saveImage(gdef.filename);
            info.bytesSource = () -> {
                try {
                    return Files.readAllBytes(imgpath);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to read image bytes", e);
                }
            };
            info.bytesCount = () -> {
                try {
                    return (int) Files.size(imgpath);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to read image informations", e);
                }
            };
        } else {
            byte[] content = worker.getImageBytes();
            info.bytesSource = () -> Arrays.copyOf(content, content.length);
            info.bytesCount = () -> content.length;
        }
    }

    private void drawOverlay() throws IOException {
        if (gdef.overlayImage != null) {
            worker.loadImage(gdef.overlayImage, 0, 0, im.xgif, im.ygif);
        }
    }

    private void gator() {
        if (!gdef.onlyGraph && gdef.showSignature) {
            worker.setTextAntiAliasing(gdef.textAntiAliasing);
            Font font = gdef.getFont(RrdGraphConstants.FONTTAG_WATERMARK);
            int x = (int) (im.xgif - 2 - worker.getFontAscent(font));
            int y = 4;
            worker.transform(x, y, Math.PI / 2);
            worker.drawString(signature, 0, 0, font, Color.LIGHT_GRAY);
            worker.reset();
            worker.setTextAntiAliasing(false);
        }
    }

    private void drawRulesAndSpans() {
        boolean found = false;
        for (PlotElement pe : gdef.plotElements) {
            if (pe instanceof HRule) {
                HRule hr = (HRule) pe;
                if (hr.value >= im.minval && hr.value <= im.maxval) {
                    int y = mapper.ytr(hr.value);
                    if (!found) {
                        worker.clip(im.xorigin + 1, im.yorigin - gdef.height - 1, gdef.width - 1, gdef.height + 2);
                        found = true;
                    }
                    worker.drawLine(im.xorigin, y, im.xorigin + im.xsize, y, hr.color, hr.stroke);
                }
            } else if (pe instanceof VRule) {
                VRule vr = (VRule) pe;
                if (vr.timestamp >= im.start && vr.timestamp <= im.end) {
                    int x = mapper.xtr(vr.timestamp);
                    if (!found) {
                        worker.clip(im.xorigin + 1, im.yorigin - gdef.height - 1, gdef.width - 1, gdef.height + 2);
                        found = true;
                    }
                    worker.drawLine(x, im.yorigin, x, im.yorigin - im.ysize, vr.color, vr.stroke);
                }
            }
            else if (pe instanceof HSpan) {
                HSpan hr = (HSpan) pe;
                int ys = mapper.ytr(hr.start);
                int ye = mapper.ytr(hr.end);
                int height = ys - ye;
                if (!found) {
                    worker.clip(im.xorigin + 1, im.yorigin - gdef.height - 1, gdef.width - 1, gdef.height + 2);
                    found = true;
                }
                worker.fillRect(im.xorigin, ys - height, im.xsize, height, hr.color);
            } else if (pe instanceof VSpan) {
                VSpan vr = (VSpan) pe;
                int xs = mapper.xtr(vr.start);
                int xe = mapper.xtr(vr.end);
                if (!found) {
                    worker.clip(im.xorigin + 1, im.yorigin - gdef.height - 1, gdef.width - 1, gdef.height + 2);
                    found = true;
                }
                worker.fillRect(xs, im.yorigin - im.ysize, xe - xs, im.ysize, vr.color);
            }
        }
        if (found)
            worker.reset();
    }

    private void drawText() {
        if (!gdef.onlyGraph) {
            worker.setTextAntiAliasing(gdef.textAntiAliasing);
            if (gdef.title != null) {
                // I2P truncate on the right only
                //int x = im.xgif / 2 - (int) (worker.getStringWidth(gdef.title, gdef.getFont(RrdGraphConstants.FONTTAG_TITLE)) / 2);
                int x = Math.max(2, im.xgif / 2 - (int) (worker.getStringWidth(gdef.title, gdef.getFont(RrdGraphConstants.FONTTAG_TITLE)) / 2));
                // I2P a little less padding on top and more on the bottom
                //int y = PADDING_TOP + (int) worker.getFontAscent(gdef.getFont(FONTTAG_TITLE));
                int y = RrdGraphConstants.PADDING_TOP * 2 / 3 + (int) worker.getFontAscent(gdef.getFont(RrdGraphConstants.FONTTAG_TITLE));
                worker.drawString(gdef.title, x, y, gdef.getFont(RrdGraphConstants.FONTTAG_TITLE), gdef.getColor(ElementsNames.font));
            }
            if (gdef.verticalLabel != null) {
                int y = im.yorigin - im.ysize / 2 + (int) worker.getStringWidth(gdef.verticalLabel, gdef.getFont(RrdGraphConstants.FONTTAG_UNIT)) / 2;
                int ascent = (int) worker.getFontAscent(gdef.getFont(RrdGraphConstants.FONTTAG_UNIT));
                worker.transform(RrdGraphConstants.PADDING_LEFT, y, -Math.PI / 2);
                worker.drawString(gdef.verticalLabel, 0, ascent,
                        gdef.getFont(RrdGraphConstants.FONTTAG_UNIT),
                        gdef.getColor(ElementsNames.font));
                worker.reset();
            }
            worker.setTextAntiAliasing(false);
        }
    }

    private void drawGrid() {
        if (!gdef.onlyGraph) {
            worker.setTextAntiAliasing(gdef.textAntiAliasing);
            Paint shade1 = gdef.getColor(ElementsNames.shadea);
            Paint shade2 = gdef.getColor(ElementsNames.shadeb);
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
                } else if (gdef.logarithmic) {
                    ok = new ValueAxisLogarithmic(this, worker, gdef.locale).draw();
                } else {
                    ok = new ValueAxis(this).draw();
                }
                if (!ok) {
                    String msg = "No Data Found";
                    worker.drawString(msg, im.xgif / 2 - (int) worker.getStringWidth(msg,
                                    gdef.getFont(RrdGraphConstants.FONTTAG_TITLE)) / 2,
                            (2 * im.yorigin - im.ysize) / 2,
                            gdef.getFont(RrdGraphConstants.FONTTAG_TITLE), gdef.getColor(ElementsNames.font));
                }
            }
            worker.setTextAntiAliasing(false);
        }
    }

    private void drawData() {
        worker.setAntiAliasing(gdef.antiAliasing);
        worker.clip(im.xorigin, im.yorigin - gdef.height - 1, gdef.width,
                gdef.height + 2);
        double areaZero = mapper.ytr((im.minval > 0.0) ? im.minval : Math.min(im.maxval, 0.0));
        double[] x = gdef.downsampler == null ? xtr(dproc.getTimestamps()) : null;
        double[] lastY = null;
        // draw line, area and stack
        for (PlotElement plotElement : gdef.plotElements) {
            if (plotElement instanceof SourcedPlotElement) {
                SourcedPlotElement source = (SourcedPlotElement) plotElement;
                double[] y;
                if (gdef.downsampler != null) {
                    DownSampler.DataSet set = gdef.downsampler.downsize(dproc.getTimestamps(),
                            source.getValues());
                    x = xtr(set.timestamps);
                    y = ytr(set.values);
                } else {
                    y = ytr(source.getValues());
                }
                if (Line.class.isAssignableFrom(source.getClass())) {
                    worker.drawPolyline(x, y, source.color, ((Line) source).stroke);
                } else if (Area.class.isAssignableFrom(source.getClass())) {
                    if (source.parent == null) {
                        worker.fillPolygon(x, areaZero, y, source.color);
                    } else {
                        worker.fillPolygon(x, lastY, y, source.color);
                        worker.drawPolyline(x, lastY, source.getParentColor(), new BasicStroke(0));
                    }
                } else if (source instanceof Stack) {
                    Stack stack = (Stack) source;
                    float width = stack.getParentLineWidth();
                    if (width >= 0F) {
                        // line
                        worker.drawPolyline(x, y, stack.color, new BasicStroke(width));
                    } else {
                        // area
                        worker.fillPolygon(x, lastY, y, stack.color);
                        worker.drawPolyline(x, lastY, stack.getParentColor(), new BasicStroke(0));
                    }
                } else {
                    // should not be here
                    throw new IllegalStateException("Unknown plot source: " + source.getClass().getName());
                }
                lastY = y;
            }
        }
        worker.reset();
        worker.setAntiAliasing(false);
    }

    private void drawAxis() {
        if (!gdef.onlyGraph) {
            Paint gridColor = gdef.getColor(ElementsNames.grid);
            Paint xaxisColor = gdef.getColor(ElementsNames.xaxis);
            Paint yaxisColor = gdef.getColor(ElementsNames.yaxis);
            Paint arrowColor = gdef.getColor(ElementsNames.arrow);
            Stroke stroke = new BasicStroke(1);
            worker.drawLine(im.xorigin + im.xsize, im.yorigin,
                    im.xorigin + im.xsize, im.yorigin - im.ysize, gridColor,
                    stroke);
            worker.drawLine(im.xorigin, im.yorigin - im.ysize,
                    im.xorigin + im.xsize, im.yorigin - im.ysize, gridColor,
                    stroke);
            worker.drawLine(im.xorigin - 4, im.yorigin, im.xorigin + im.xsize + 4,
                    im.yorigin, xaxisColor, stroke);
            worker.drawLine(im.xorigin, im.yorigin + 4, im.xorigin,
                    im.yorigin - im.ysize - 4, yaxisColor, stroke);

            // I2P skip arrowheads if transparent
            if (((Color)arrowColor).getAlpha() == 0)
                return;

            //Do X axis arrow
            double[] xArrowX = { im.xorigin + im.xsize + 4,
                    im.xorigin + im.xsize + 9, im.xorigin + im.xsize + 4, };
            double[] xArrowY = { im.yorigin - 3, im.yorigin, im.yorigin + 3, };
            worker.fillPolygon(xArrowX, im.yorigin + 3.0, xArrowY, arrowColor);

            //Do y axis arrow
            double[] yArrowX = { im.xorigin - 3, im.xorigin, im.xorigin + 3, };
            double[] yArrowY = { im.yorigin - im.ysize - 4,
                    im.yorigin - im.ysize - 9, im.yorigin - im.ysize - 4, };
            worker.fillPolygon(yArrowX, im.yorigin - im.ysize - 4.0, yArrowY, arrowColor);
        }
    }

    private void drawBackground() throws IOException {
        worker.fillRect(0, 0, im.xgif, im.ygif, gdef.getColor(ElementsNames.back));
        if (gdef.backgroundImage != null) {
            worker.loadImage(gdef.backgroundImage, 0, 0, im.xgif, im.ygif);
        }
        if (gdef.canvasImage != null) {
            worker.loadImage(gdef.canvasImage, im.xorigin, im.yorigin - im.ysize, im.xsize, im.ysize);
        }
        worker.fillRect(im.xorigin, im.yorigin - im.ysize, im.xsize, im.ysize, gdef.getColor(ElementsNames.canvas));
    }

    private void createImageWorker() {
        worker.resize(im.xgif, im.ygif);
    }

    private void placeLegends() {
        if (!gdef.noLegend && !gdef.onlyGraph) {
            int border = (int) (getFontCharWidth(RrdGraphConstants.FontTag.LEGEND) * RrdGraphConstants.PADDING_LEGEND);
            LegendComposer lc = new LegendComposer(this, border, im.ygif, im.xgif - 2 * border);
            im.ygif = lc.placeComments() + RrdGraphConstants.PADDING_BOTTOM;
        }
    }

    private void initializeLimits() {
        im.xsize = gdef.width;
        im.ysize = gdef.height;
        im.unitslength = gdef.unitsLength;

        if (gdef.onlyGraph) {
            im.xorigin = 0;
        } else {
            im.xorigin = (int) (RrdGraphConstants.PADDING_LEFT + im.unitslength * getFontCharWidth(
                    RrdGraphConstants.FONTTAG_AXIS));
        }

        if (!gdef.onlyGraph && gdef.verticalLabel != null) {
            im.xorigin += (int) getFontHeight(RrdGraphConstants.FONTTAG_UNIT);
        }

        if (gdef.onlyGraph) {
            im.yorigin = im.ysize;
        } else {
            im.yorigin = RrdGraphConstants.PADDING_TOP + im.ysize;
        }

        if (!gdef.onlyGraph && gdef.title != null) {
            im.yorigin += (int) (getFontHeight(RrdGraphConstants.FONTTAG_TITLE) + RrdGraphConstants.PADDING_TITLE);
        }

        if (gdef.onlyGraph) {
            im.xgif = im.xsize;
            im.ygif = im.yorigin;
        } else {
            im.xgif = RrdGraphConstants.PADDING_RIGHT + im.xsize + im.xorigin;
            im.ygif = im.yorigin + (int) (RrdGraphConstants.PADDING_PLOT * getFontHeight(
                    RrdGraphConstants.FONTTAG_AXIS));
        }
    }

    private void removeOutOfRangeRules() {
        for (PlotElement plotElement : gdef.plotElements) {
            if (plotElement instanceof HRule) {
                ((HRule) plotElement).setLegendVisibility(im.minval, im.maxval, gdef.forceRulesLegend);
            } else if (plotElement instanceof VRule) {
                ((VRule) plotElement).setLegendVisibility(im.start, im.end, gdef.forceRulesLegend);
            }
        }
    }

    private void removeOutOfRangeSpans() {
        for (PlotElement plotElement : gdef.plotElements) {
            if (plotElement instanceof HSpan) {
                ((HSpan) plotElement).setLegendVisibility(im.minval, im.maxval, gdef.forceRulesLegend);
            } else if (plotElement instanceof VSpan) {
                ((VSpan) plotElement).setLegendVisibility(im.start, im.end, gdef.forceRulesLegend);
            }
        }
    }

    private void expandValueRange() {
        im.ygridstep = (gdef.valueAxisSetting != null) ?
                                        gdef.valueAxisSetting.gridStep :
                                        Double.NaN;
        im.ylabfact = (gdef.valueAxisSetting != null) ?
                                       gdef.valueAxisSetting.labelFactor :
                                       0;
        if (!gdef.rigid && !gdef.logarithmic) {
            double scaledMin;
            double scaledMax;
            double adj;
            if (Double.isNaN(im.ygridstep)) {
                if (gdef.altYMrtg) { /* mrtg */
                    im.decimals = Math.ceil(
                            Math.log10(Math.max(Math.abs(im.maxval), Math.abs(im.minval))));
                    im.quadrant = 0;
                    if (im.minval < 0) {
                        im.quadrant = 2;
                        if (im.maxval <= 0) {
                            im.quadrant = 4;
                        }
                    }
                    switch (im.quadrant) {
                    case 2:
                        im.scaledstep = Math.ceil(
                                50 * Math.pow(10, -(im.decimals)) * Math.max(Math.abs(im.maxval),
                                        Math.abs(im.minval))) * Math.pow(10, im.decimals - 2);
                        scaledMin = -2 * im.scaledstep;
                        scaledMax = 2 * im.scaledstep;
                        break;
                    case 4:
                        im.scaledstep = Math.ceil(
                                25 * Math.pow(10, -(im.decimals)) * Math.abs(im.minval)) * Math.pow(
                                10, im.decimals - 2);
                        scaledMin = -4 * im.scaledstep;
                        scaledMax = 0;
                        break;
                    default: /* quadrant 0 */
                        im.scaledstep = Math.ceil(
                                25 * Math.pow(10, -(im.decimals)) * im.maxval) * Math.pow(10,
                                im.decimals - 2);
                        scaledMin = 0;
                        scaledMax = 4 * im.scaledstep;
                        break;
                    }
                    im.minval = scaledMin;
                    im.maxval = scaledMax;
                } else if (gdef.altAutoscale || (gdef.altAutoscaleMin && gdef.altAutoscaleMax)) {
                    /* measure the amplitude of the function. Make sure that
                       graph boundaries are slightly higher than max/min vals,
                       so we can see amplitude on the graph */
                    double delt;
                    double fact;

                    delt = im.maxval - im.minval;
                    adj = delt * 0.1;
                    fact = 2.0 * Math.pow(10.0, Math.floor(
                            Math.log10(Math.max(Math.abs(im.minval), Math.abs(im.maxval)))) - 2);
                    if (delt < fact) {
                        adj = (fact - delt) * 0.55;
                    }
                    im.minval -= adj;
                    im.maxval += adj;
                } else if (gdef.altAutoscaleMin) {
                    /* measure the amplitude of the function. Make sure that
                       graph boundaries are slightly lower than min vals,
                       so we can see amplitude on the graph */
                    adj = (im.maxval - im.minval) * 0.1;
                    im.minval -= adj;
                } else if (gdef.altAutoscaleMax) {
                    /* measure the amplitude of the function. Make sure that
                       graph boundaries are slightly higher than max vals,
                       so we can see amplitude on the graph */
                    adj = (im.maxval - im.minval) * 0.1;
                    im.maxval += adj;
                } else {
                    scaledMin = im.minval / im.magfact;
                    scaledMax = im.maxval / im.magfact;
                    for (int i = 1; SENSIBLE_VALUES[i] > 0; i++) {
                        if (SENSIBLE_VALUES[i - 1] >= scaledMin && SENSIBLE_VALUES[i] <= scaledMin) {
                            im.minval = SENSIBLE_VALUES[i] * im.magfact;
                        }
                        if (-SENSIBLE_VALUES[i - 1] <= scaledMin && -SENSIBLE_VALUES[i] >= scaledMin) {
                            im.minval = -SENSIBLE_VALUES[i - 1] * im.magfact;
                        }
                        if (SENSIBLE_VALUES[i - 1] >= scaledMax && SENSIBLE_VALUES[i] <= scaledMax) {
                            im.maxval = SENSIBLE_VALUES[i - 1] * im.magfact;
                        }
                        if (-SENSIBLE_VALUES[i - 1] <= scaledMax && -SENSIBLE_VALUES[i] >= scaledMax) {
                            im.maxval = -SENSIBLE_VALUES[i] * im.magfact;
                        }
                    }
                }
            } else {
                im.minval = im.ylabfact * im.ygridstep * Math.floor(
                        im.minval / (im.ylabfact * im.ygridstep));
                im.maxval = im.ylabfact * im.ygridstep * Math.ceil(
                        im.maxval / (im.ylabfact * im.ygridstep));
            }

        }
    }

    private void identifySiUnit() {
        im.unitsexponent = gdef.unitsExponent;
        im.base = gdef.base;
        if (!gdef.logarithmic) {
            double digits;
            if (im.unitsexponent != Integer.MAX_VALUE) {
                digits = Math.floor(im.unitsexponent / 3.0);
            } else {
                digits = Math.floor(
                        Math.log(Math.max(Math.abs(im.minval), Math.abs(im.maxval))) / Math.log(
                                im.base));
            }
            im.magfact = Math.pow(im.base, digits);
            if (((digits + SYMBOLS_CENTER) < SYMBOLS.length) && ((digits + SYMBOLS_CENTER) >= 0)) {
                im.symbol = SYMBOLS[(int) digits + SYMBOLS_CENTER];
            } else {
                im.symbol = '?';
            }
        }
    }

    private void findMinMaxValues() {
        double minval = Double.NaN;
        double maxval = Double.NaN;
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
        if (Double.isNaN(
                im.minval) || ((!gdef.logarithmic && !gdef.rigid) && im.minval > minval)) {
            im.minval = minval;
        }
        if (Double.isNaN(im.maxval) || (!gdef.rigid && im.maxval < maxval)) {
            if (gdef.logarithmic) {
                im.maxval = maxval * 1.1;
            } else {
                im.maxval = maxval;
            }
        }
        /* make sure min is smaller than max */
        if (im.minval > im.maxval) {
            im.minval = 0.99 * im.maxval;
        }
        /* make sure min and max are not equal */
        if (Math.abs(im.minval - im.maxval) < .0000001)  {
            im.maxval *= 1.01;
            if (!gdef.logarithmic) {
                im.minval *= 0.99;
            }
            /* make sure min and max are not both zero */
            if (im.maxval == 0.0) {
                im.maxval = 1.0;
            }
        }
        if (gdef.logarithmic) {
            im.log = LogService.resolve(im);
        }
    }

    private void calculatePlotValues() {
        for (PlotElement pe : gdef.plotElements) {
            if (pe instanceof SourcedPlotElement) {
                ((SourcedPlotElement) pe).assignValues(dproc);
            }
        }
    }

    private void resolveTextElements() {
        ValueScaler valueScaler = new ValueScaler(gdef.base);
        for (CommentText comment : gdef.comments) {
            comment.resolveText(gdef.locale, dproc, valueScaler);
        }
    }

    private void fetchData() throws IOException {
        dproc.setPixelCount(gdef.width);
        if (gdef.poolUsed) {
            dproc.setPoolUsed(true);
            dproc.setPool(gdef.getPool());
        }
        dproc.setTimeZone(gdef.tz);
        if (gdef.step > 0) {
            dproc.setStep(gdef.step);
            dproc.setFetchRequestResolution(gdef.step);
        }
        for (Source src : gdef.sources) {
            src.requestData(dproc);
        }
        dproc.processData();
        im.start = gdef.startTime;
        im.end = gdef.endTime;
    }

    private boolean lazyCheck() throws IOException {
        // redraw if lazy option is not set or file does not exist
        if (!gdef.lazy || !Util.fileExists(gdef.filename)) {
            return false; // 'false' means 'redraw'
        }
        // redraw if not enough time has passed
        long secPerPixel = (gdef.endTime - gdef.startTime) / gdef.width;
        long elapsed = Util.getTimestamp() - Util.getLastModifiedTime(gdef.filename);
        return elapsed <= secPerPixel;
    }

    private void drawLegend() {
        if (!gdef.onlyGraph && !gdef.noLegend) {
            worker.setTextAntiAliasing(gdef.textAntiAliasing);
            int ascent = (int) worker.getFontAscent(gdef.getFont(RrdGraphConstants.FONTTAG_LEGEND));
            int box = (int) getBox();
            int boxSpace = (int) (getBoxSpace());
            for (CommentText c : gdef.comments) {
                if (c.isValidGraphElement()) {
                    int x = c.x;
                    int y = c.y + ascent;
                    if (c instanceof LegendText) {
                        // draw with BOX
                        worker.fillRect(x, y - box, box, box, gdef.getColor(ElementsNames.frame));
                        Paint bc = gdef.getColor(ElementsNames.back);
                        Paint lc = ((LegendText) c).legendColor;
                        boolean bt = bc.getTransparency() != Transparency.OPAQUE;
                        boolean lt = lc.getTransparency() != Transparency.OPAQUE;
                        // no use drawing unless both the two on top have some transparency
                        if (bt && lt)
                            worker.fillRect(x + 1, y - box + 1, box - 2, box - 2, gdef.getColor(ElementsNames.canvas));
                        // no use drawing unless the one on top has some transparency
                        if (lt)
                            worker.fillRect(x + 1, y - box + 1, box - 2, box - 2, bc);
                        worker.fillRect(x + 1, y - box + 1, box - 2, box - 2, lc);
                        worker.drawString(c.resolvedText, x + boxSpace, y,
                                gdef.getFont(RrdGraphConstants.FONTTAG_LEGEND),
                                gdef.getColor(ElementsNames.font));
                    } else {
                        worker.drawString(c.resolvedText, x, y, gdef.getFont(RrdGraphConstants.FONTTAG_LEGEND),
                                gdef.getColor(ElementsNames.font));
                    }
                }
            }
            worker.setTextAntiAliasing(false);
        }
    }

    // helper methods

    double getFontHeight(RrdGraphConstants.FontTag fonttag) {
        return worker.getFontHeight(gdef.getFont(fonttag));
    }

    double getFontCharWidth(RrdGraphConstants.FontTag fonttag) {
        return worker.getStringWidth("a", gdef.getFont(fonttag));
    }

    double getInterlegendSpace() {
        return getFontCharWidth(RrdGraphConstants.FONTTAG_LEGEND) * RrdGraphConstants.LEGEND_INTERSPACING;
    }

    double getLeading() {
        return getFontHeight(RrdGraphConstants.FONTTAG_LEGEND) * RrdGraphConstants.LEGEND_LEADING;
    }

    double getSmallLeading() {
        return getFontHeight(RrdGraphConstants.FONTTAG_LEGEND) * RrdGraphConstants.LEGEND_LEADING_SMALL;
    }

    double getBoxSpace() {
        return Math.ceil(getFontHeight(RrdGraphConstants.FONTTAG_LEGEND) * RrdGraphConstants.LEGEND_BOX_SPACE);
    }

    private double getBox() {
        return getFontHeight(RrdGraphConstants.FONTTAG_LEGEND) * RrdGraphConstants.LEGEND_BOX;
    }

    private double[] xtr(long[] timestamps) {
        double[] timestampsDev = new double[2 * timestamps.length - 1];
        for (int i = 0, j = 0; i < timestamps.length; i += 1, j += 2) {
            timestampsDev[j] = mapper.xtr(timestamps[i]);
            if (i < timestamps.length - 1) {
                timestampsDev[j + 1] = timestampsDev[j];
            }
        }
        return timestampsDev;
    }

    private double[] ytr(double[] values) {
        double[] valuesDev = new double[2 * values.length - 1];
        for (int i = 0, j = 0; i < values.length; i += 1, j += 2) {
            if (Double.isNaN(values[i])) {
                valuesDev[j] = Double.NaN;
            } else {
                valuesDev[j] = mapper.ytr(values[i]);
            }
            if (j > 0) {
                valuesDev[j - 1] = valuesDev[j];
            }
        }
        return valuesDev;
    }

}
