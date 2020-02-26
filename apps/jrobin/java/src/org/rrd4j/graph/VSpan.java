package org.rrd4j.graph;

import java.awt.Paint;

class VSpan extends Span {
    final long start;
    final long end;

    VSpan(long start, long end, Paint color, LegendText legend) {
        super(color, legend);
        this.start = start;
        this.end = end;
        assert(start < end);
    }

    private boolean checkRange(long v, long min, long max) {
        return v >= min && v <= max;
    }

    void setLegendVisibility(long min, long max, boolean forceLegend) {
        legend.enabled = legend.enabled && (forceLegend
                || checkRange(start, min, max)
                || checkRange(end, min, max));
    }
}
