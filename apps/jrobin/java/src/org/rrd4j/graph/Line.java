package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Paint;

class Line extends SourcedPlotElement {
    final BasicStroke stroke;

    Line(String srcName, Paint color, BasicStroke stroke, SourcedPlotElement parent) {
        super(srcName, color, parent);
        this.stroke = stroke;
    }

}
