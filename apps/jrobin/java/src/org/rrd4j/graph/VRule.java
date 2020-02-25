package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Paint;

class VRule extends Rule {
    final long timestamp;

    VRule(long timestamp, Paint color, LegendText legend, BasicStroke stroke) {
        super(color, legend, stroke);
        this.timestamp = timestamp;
    }

    void setLegendVisibility(long minval, long maxval, boolean forceLegend) {
        legend.enabled &= (forceLegend || (timestamp >= minval && timestamp <= maxval));
    }
}
