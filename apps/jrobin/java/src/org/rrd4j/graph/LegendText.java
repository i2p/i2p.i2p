package org.rrd4j.graph;

import java.awt.*;

class LegendText extends CommentText {
    final Paint legendColor;

    LegendText(Paint legendColor, String text) {
        super(text);
        this.legendColor = legendColor;
    }
}
