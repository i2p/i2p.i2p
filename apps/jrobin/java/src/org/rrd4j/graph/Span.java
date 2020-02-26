package org.rrd4j.graph;

import java.awt.*;

class Span extends PlotElement {
    final LegendText legend;

    Span(Paint color, LegendText legend) {
        super(color);
        this.legend = legend;
    }
}
