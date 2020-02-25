package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Paint;

class Rule extends PlotElement {
    final LegendText legend;
    final BasicStroke stroke;

    Rule(Paint color, LegendText legend, BasicStroke stroke) {
        super(color);
        this.legend = legend;
        this.stroke = stroke;
    }
}
