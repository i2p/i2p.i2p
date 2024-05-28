package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Paint;
import java.math.BigDecimal;
import java.math.MathContext;

import org.rrd4j.core.Util;

class ValueAxis extends Axis {
    private static final YLabel[] ylabels = {
        new YLabel(0.1, 1, 2, 5, 10),
        new YLabel(0.2, 1, 5, 10, 20),
        new YLabel(0.5, 1, 2, 4, 10),
        new YLabel(1.0, 1, 2, 5, 10),
        new YLabel(2.0, 1, 5, 10, 20),
        new YLabel(5.0, 1, 2, 4, 10),
        new YLabel(10.0, 1, 2, 5, 10),
        new YLabel(20.0, 1, 5, 10, 20),
        new YLabel(50.0, 1, 2, 4, 10),
        new YLabel(100.0, 1, 2, 5, 10),
        new YLabel(200.0, 1, 5, 10, 20),
        new YLabel(500.0, 1, 2, 4, 10),
        new YLabel(1000.0, 1, 2, 5, 10),
        new YLabel(2000.0, 1, 5, 10, 20),
        new YLabel(5000.0, 1, 2, 4, 10),
        new YLabel(10000.0, 1, 2, 5, 10),
        new YLabel(20000.0, 1, 5, 10, 20),
        new YLabel(50000.0, 1, 2, 4, 10),
        new YLabel(100000.0, 1, 2, 5, 10),
        new YLabel(0.0, 0, 0, 0, 0)
    };

    private final ImageParameters im;
    private final ImageWorker worker;
    private final RrdGraphDef gdef;
    private final Mapper mapper;

    ValueAxis(RrdGraph rrdGraph) {
        this(rrdGraph, rrdGraph.worker);
    }

    ValueAxis(RrdGraph rrdGraph, ImageWorker worker) {
        this.im = rrdGraph.im;
        this.gdef = rrdGraph.gdef;
        this.worker = worker;
        this.mapper = rrdGraph.mapper;
    }

    boolean draw() {
        Font font = gdef.getFont(FONTTAG_AXIS);
        Paint gridColor = gdef.getColor(ElementsNames.grid);
        Paint mGridColor = gdef.getColor(ElementsNames.mgrid);
        Paint fontColor = gdef.getColor(ElementsNames.font);
        int labelOffset = (int) (worker.getFontAscent(font) / 2);
        int labfact;
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
                    labfmt = Util.sprintf(gdef.locale, "%%%d.%df", decimals - fractionals + 1, -fractionals + 1);
                }
                else {
                    labfmt = Util.sprintf(gdef.locale, "%%%d.1f", decimals + 1);
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
                YLabel selectedYLabel = null;
                while(selectedYLabel == null) {
                    selectedYLabel = findYLabel(minimumLabelCount);
                    minimumLabelCount--;
                }
                gridstep = selectedYLabel.grid * im.magfact;
                labfact = findLabelFactor(selectedYLabel);
                if(labfact == -1) {
                    // as a fallback, use the largest label factor of the selected label
                    labfact = selectedYLabel.labelFacts[3];
                }
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
        boolean fractional = isFractional(scaledstep, labfact);
        // I2P skip ticks if zero width
        boolean ticks = ((BasicStroke)gdef.tickStroke).getLineWidth() > 0;
        for (int i = sgrid; i <= egrid; i++) {
            int y = mapper.ytr(gridstep * i);
            if (y >= im.yorigin - im.ysize && y <= im.yorigin) {
                if (i % labfact == 0) {
                    String graph_label;
                    if (i == 0 || im.symbol == ' ') {
                        if (fractional) {
                            if (i != 0 && gdef.altYGrid) {
                                graph_label = Util.sprintf(gdef.locale, labfmt, scaledstep * i);
                            }
                            else {
                                graph_label = Util.sprintf(gdef.locale, "%4.1f", scaledstep * i);
                            }
                        }
                        else {
                            graph_label = Util.sprintf(gdef.locale, "%4.0f", scaledstep * i);
                        }
                    }
                    else {
                        if (fractional) {
                            graph_label = Util.sprintf(gdef.locale, "%4.1f %c", scaledstep * i, im.symbol);
                        }
                        else {
                            graph_label = Util.sprintf(gdef.locale, "%4.0f %c", scaledstep * i, im.symbol);
                        }
                    }
                    int length = (int) (worker.getStringWidth(graph_label, font));
                    worker.drawString(graph_label, x0 - length - PADDING_VLABEL, y + labelOffset, font, fontColor);
                    if (ticks) {
                        worker.drawLine(x0 - 2, y, x0 + 2, y, mGridColor, gdef.tickStroke);
                        worker.drawLine(x1 - 2, y, x1 + 2, y, mGridColor, gdef.tickStroke);
                    }
                    worker.drawLine(x0, y, x1, y, mGridColor, gdef.gridStroke);
                }
                else if (!(gdef.noMinorGrid)) {
                    if (ticks) {
                        worker.drawLine(x0 - 1, y, x0 + 1, y, gridColor, gdef.tickStroke);
                        worker.drawLine(x1 - 1, y, x1 + 1, y, gridColor, gdef.tickStroke);
                    }
                    worker.drawLine(x0, y, x1, y, gridColor, gdef.gridStroke);
                }
            }
        }
        return true;
    }

    /**
     * Finds an acceptable YLabel object for the current graph
     * If the graph covers positive and negative on the y-axis, then
     * desiredMinimumLabelCount is checked as well, to ensure the chosen YLabel definition
     * will result in the required number of labels
     * <p>
     * Returns null if none are acceptable (none the right size or with
     * enough labels)
     */
    private YLabel findYLabel(int desiredMinimumLabelCount) {
        double scaledrange = this.getScaledRange();
        int labelFactor;
        //Check each YLabel definition to see if it's acceptable
        for (int i = 0; ylabels[i].grid > 0; i++) {
            YLabel thisYLabel = ylabels[i];
            //First cut is whether this gridstep would give enough space per gridline
            if (this.getPixelsPerGridline(thisYLabel) > 5 ) {
                //Yep; now we might have to check the number of labels
                if(im.minval < 0.0 && im.maxval > 0.0) {
                    //The graph covers positive and negative values, so we need the
                    // desiredMinimumLabelCount number of labels, which is going to
                    // usually be 3, then maybe 2, then only as a last resort, 1. 
                    // So, we need to find out what the label factor would be
                    // if we chose this ylab definition
                    labelFactor = findLabelFactor(thisYLabel);
                    if(labelFactor == -1) {
                        //Default to too many to satisfy the label count test, unless we're looking for just 1	
                        // in which case be sure to satisfy the label count test
                        labelFactor = desiredMinimumLabelCount==1?1:desiredMinimumLabelCount+1; 
                    }
                    //Adding one?  Think fenceposts (need one more than just dividing length by space between)
                    int labelCount = ((int)(scaledrange/thisYLabel.grid)/labelFactor)+1;
                    if(labelCount > desiredMinimumLabelCount) {
                        return thisYLabel; //Enough pixels, *and* enough labels
                    }

                } else {
                    //Only positive or negative on the graph y-axis.  No need to
                    // care about the label count.
                    return thisYLabel;
                }
            }
        }

        double val = 1;
        while(val < scaledrange) {
            val = val * 10;
        }
        return new YLabel(val/10, 1, 2, 5, 10);
    }

    /**
     * Find the smallest labelFactor acceptable (can fit labels) for the given YLab definition
     * Returns the label factor if one is ok, otherwise returns -1 if none are acceptable
     */
    private int findLabelFactor(YLabel thisYLabel) {
        int pixel = this.getPixelsPerGridline(thisYLabel);
        int fontHeight = (int) Math.ceil(worker.getFontHeight(gdef.getFont(FONTTAG_AXIS)));
        for (int j = 0; j < 4; j++) {
            if (pixel * thisYLabel.labelFacts[j] >= 2 * fontHeight) {
                return thisYLabel.labelFacts[j];
            }
        }
        return -1;
    }

    /**
     * Finds the number of pixels per gridline that the given YLabel definition will result in
     */
    private int getPixelsPerGridline(YLabel thisYLabel) {
        double scaledrange = this.getScaledRange();
        return (int) (im.ysize / (scaledrange / thisYLabel.grid));
    }

    private double getScaledRange() {
        double range = im.maxval - im.minval;
        return range / im.magfact;
    }

    /**
     * Returns true if some or all labels have fractional part (other than zero).
     */
    private static boolean isFractional(double scaledstep, int labfact) {
        if (scaledstep >= 1) {
            return false;
        }
        BigDecimal bd = BigDecimal.valueOf(scaledstep)
                .multiply(BigDecimal.valueOf(labfact), MathContext.DECIMAL32);
        return !(bd.signum() == 0 || bd.scale() <= 0 || bd.stripTrailingZeros().scale() <= 0);
    }

    static class YLabel {
        final double grid;
        final int[] labelFacts;

        YLabel(double grid, int lfac1, int lfac2, int lfac3, int lfac4) {
            this.grid = grid;
            labelFacts = new int[]{lfac1, lfac2, lfac3, lfac4};
        }
    }
}
