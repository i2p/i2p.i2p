package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Paint;

class HRule extends Rule {
    final double value;

    HRule(double value, Paint color, LegendText legend, BasicStroke stroke) {
        super(color, legend, stroke);
        this.value = value;
    }

    void setLegendVisibility(double minval, double maxval, boolean forceLegend) {
        legend.enabled &= (forceLegend || (value >= minval && value <= maxval));
    }
}
