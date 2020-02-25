package org.rrd4j.graph;

import java.awt.Paint;

class HSpan extends Span {
    final double start;
    final double end;

    HSpan(double start, double end, Paint color, LegendText legend) {
        super(color, legend);
        this.start = start;
        this.end = end;
        assert(start < end);
    }

    private boolean checkRange(double v, double min, double max) {
        return v >= min && v <= max;
    }

    void setLegendVisibility(double min, double max, boolean forceLegend) {
        legend.enabled = legend.enabled && (forceLegend
                || checkRange(start, min, max)
                || checkRange(end, min, max));
    }
}
