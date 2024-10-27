package org.rrd4j.graph;

import java.awt.*;
import java.util.Locale;

class ValueAxisLogarithmic extends Axis {
    private static final double[][] yloglab = {
        {1e9, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {1e3, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {1e1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {1e1, 1, 2.5, 5, 7.5, 0, 0, 0, 0, 0, 0, 0},
        {1e1, 1, 2, 4, 6, 8, 0, 0, 0, 0, 0, 0},
        {1e1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
    };

    @FunctionalInterface
    private interface IntDoubleLabelConsumer {
        void accept(int a, double b, String formatPattern);
        default void accept(int a, double b) {
            accept(a, b, "%.0e");
        }
    }

    @FunctionalInterface
    private interface IntDoubleLineConsumer {
        void accept(int a, double b, Paint color);
    }

    private final ImageParameters im;
    private final ImageWorker worker;
    private final RrdGraphDef gdef;
    private final int fontHeight;
    private final Mapper mapper;
    private final Locale locale;

    /**
     * Used for tests
     *
     * @param rrdGraph
     * @param worker
     */
    ValueAxisLogarithmic(RrdGraph rrdGraph, ImageWorker worker, Locale locale) {
        this.im = rrdGraph.im;
        this.gdef = rrdGraph.gdef;
        this.worker = worker;
        this.fontHeight = (int) Math.ceil(worker.getFontHeight(gdef.getFont(FONTTAG_AXIS)));
        this.mapper = new Mapper(this.gdef, this.im);
        this.locale = locale;
    }

    ValueAxisLogarithmic(RrdGraphGenerator rrdGraph, ImageWorker worker, Locale locale) {
        this.im = rrdGraph.im;
        this.gdef = rrdGraph.gdef;
        this.worker = worker;
        this.fontHeight = (int) Math.ceil(worker.getFontHeight(gdef.getFont(FONTTAG_AXIS)));
        this.mapper = rrdGraph.mapper;
        this.locale = locale;
    }

    private double findStart(double positive, int idx) {
        return Math.pow(10, im.log.applyAsDouble(positive) - im.log.applyAsDouble(positive) % im.log.applyAsDouble(yloglab[idx][0]));
    }

    boolean draw() {
        Font font = gdef.getFont(FONTTAG_AXIS);
        Paint gridColor = gdef.getColor(ElementsNames.grid);
        Paint mGridColor = gdef.getColor(ElementsNames.mgrid);
        Paint fontColor = gdef.getColor(ElementsNames.font);
        int labelOffset = (int) (worker.getFontAscent(font) / 2);

        if (im.maxval == im.minval) {
            return false;
        }
        double pixpex = im.ysize / (im.log.applyAsDouble(im.maxval) - im.log.applyAsDouble(im.minval));
        if (Double.isNaN(pixpex)) {
            return false;
        }
        int minoridx = 0;
        int majoridx = 0;

        // Find the index in yloglab for major and minor grid
        for (int i = 0; yloglab[i][0] > 0; i++) {
            double minstep = Math.log10(yloglab[i][0]);
            for (int ii = 1; yloglab[i][ii + 1] > 0; ii++) {
                if (yloglab[i][ii + 2] == 0) {
                    minstep = Math.log10(yloglab[i][ii + 1]) - Math.log10(yloglab[i][ii]);
                    break;
                }
            }
            double pixperstep = pixpex * minstep;
            if (pixperstep > 5) {
                minoridx = i;
            }
            if (pixperstep > 2 * fontHeight) {
                majoridx = i;
            }
        }

        double positiveMin = Math.max(im.minval, 0.0);
        int x0 = im.xorigin;
        int x1 = x0 + im.xsize;
        if (yloglab[minoridx][0] == 0 || yloglab[majoridx][0] == 0) {
            return false;
        }
        String zeroFormatted = String.format(locale, "%.0e", 0.0);
        IntDoubleLabelConsumer drawAxisLabel = (y, v, f) -> {
            String graphLabel = String.format(locale, f, v);
            if (zeroFormatted.equals(graphLabel)) {
                graphLabel = String.format(locale, "%.0f", v);
            }
            int length = (int) (worker.getStringWidth(graphLabel, font));
            worker.drawString(graphLabel, x0 - length - PADDING_VLABEL, y + labelOffset, font, fontColor);
        };
        IntDoubleLineConsumer drawAxisLines = (y, v, p) -> {
            if (gdef.drawTicks()) {
                worker.drawLine(x0 - 1, y, x0 + 1, y, p, gdef.tickStroke);
                worker.drawLine(x1 - 1, y, x1 + 1, y, p, gdef.tickStroke);
            }
            worker.drawLine(x0, y, x1, y, p, gdef.gridStroke);
        };


        // Draw minor grid for positive values
        for (double value = findStart(positiveMin, minoridx);
                value <= im.maxval;
                value *= yloglab[minoridx][0]) {
            if (value < positiveMin) continue;
            int i = 0;
            while (yloglab[minoridx][++i] > 0) {
                int y = mapper.ytr(value * yloglab[minoridx][i]);
                if (y <= im.yorigin - im.ysize) {
                    break;
                }
                drawAxisLines.accept(y, value, gridColor);
            }
        }

        // Draw minor grid for negative values
        double negativeMin = -1.0 * (Math.min(im.maxval, 0.0));
        for (double value = findStart(negativeMin, minoridx);
                value <= -1.0 * im.minval;
                value *= yloglab[minoridx][0]) {
            if (value < negativeMin) continue;
            int i = 0;
            while (yloglab[minoridx][++i] > 0) {
                int y = mapper.ytr(-1.0 * value * yloglab[minoridx][i]);
                if (y <= im.yorigin - im.ysize) {
                    break;
                }
                drawAxisLines.accept(y, value, gridColor);
            }
        }

        // If it has positive and negative, always have a tick mark at 0
        boolean skipFirst = false;
        if (im.minval < 0.0 && im.maxval > 0.0) {
            skipFirst = true;
            int y = mapper.ytr(0.0);
            drawAxisLines.accept(y, 0.0, mGridColor);
            drawAxisLabel.accept(y, 0.0);
        }

        // Draw major grid for positive values
        int iter = 0;
        int lasty = Integer.MAX_VALUE;
        for (double value = findStart(positiveMin, majoridx);
                value <= im.maxval;
                value *= yloglab[majoridx][0]) {
            if (value < positiveMin) {
                continue;
            }
            ++iter;
            if (skipFirst && iter == 1) {
                continue;
            }
            int i = 0;
            while (yloglab[majoridx][++i] > 0) {
                int y = mapper.ytr(value * yloglab[majoridx][i]);
                if (y <= im.yorigin - im.ysize) {
                    break;
                }
                // Avoid collision of labels
                if ((lasty - y) > fontHeight) {
                    drawAxisLines.accept(y, value, mGridColor);
                    drawAxisLabel.accept(y, value * yloglab[majoridx][i]);
                    lasty = y;
                }
            }
        }

        // Draw major grid for negative values
        iter = 0;
        for (double value = findStart(negativeMin, majoridx);
                value <= -1.0 * im.minval;
                value *= yloglab[majoridx][0]) {
            if (value < negativeMin) {
                continue;
            }
            ++iter;
            if (skipFirst && iter == 1) {
                continue;
            }
            int i = 0;
            while (yloglab[majoridx][++i] > 0) {
                int y = mapper.ytr(-1.0 * value * yloglab[majoridx][i]);
                if (y <= im.yorigin - im.ysize) {
                    break;
                }
                drawAxisLines.accept(y, value, mGridColor);
                drawAxisLabel.accept(y, -1.0 * value * yloglab[majoridx][i]);
            }
        }

        return true;
    }

}
