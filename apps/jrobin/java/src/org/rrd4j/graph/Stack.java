package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;

import java.awt.*;

class Stack extends SourcedPlotElement {

    Stack(SourcedPlotElement parent, String srcName, Paint color) {
        super(srcName, color, parent);
    }

    void assignValues(DataProcessor dproc) {
        double[] parentValues = parent.getValues();
        double[] procValues = dproc.getValues(srcName);
        values = new double[procValues.length];
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(parentValues[i])) {
                values[i] = procValues[i];
            }
            else if (Double.isNaN(procValues[i])){
                values[i] = parentValues[i];
            }
            else {
                values[i] = parentValues[i] + procValues[i];
                
            }
        }
    }

    float getParentLineWidth() {
        if (parent instanceof Line) {
            return ((Line) parent).stroke.getLineWidth();
        }
        else if (parent instanceof Area) {
            return -1F;
        }
        else /* if(parent instanceof Stack) */ {
            return ((Stack) parent).getParentLineWidth();
        }
    }

    Paint getParentColor() {
        return parent.color;
    }
}
