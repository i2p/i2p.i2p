package org.rrd4j.graph;

import org.rrd4j.core.Util;
import org.rrd4j.data.DataProcessor;

import java.awt.*;

class SourcedPlotElement extends PlotElement {
    final String srcName;
    final SourcedPlotElement parent;
    double[] values;

    SourcedPlotElement(String srcName, Paint color) {
        super(color);
        this.srcName = srcName;
        this.parent = null;
    }

    SourcedPlotElement(String srcName, Paint color, SourcedPlotElement parent) {
        super(color);
        this.srcName = srcName;
        this.parent = parent;
    }

    void assignValues(DataProcessor dproc) {
        if(parent == null) {
            values = dproc.getValues(srcName);
        }
        else {
            values = stackValues(dproc);
        }
    }

    double[] stackValues(DataProcessor dproc) {
        double[] parentValues = parent.getValues();
        double[] procValues = dproc.getValues(srcName);
        double[] stacked = new double[procValues.length];
        for (int i = 0; i < stacked.length; i++) {
            if (Double.isNaN(parentValues[i])) {
                stacked[i] = procValues[i];
            }
            else if (Double.isNaN(procValues[i])){
                stacked[i] = parentValues[i];
            }
            else {
                stacked[i] = parentValues[i] + procValues[i];
            }
        }
        return stacked;
    }

    Paint getParentColor() {
        return parent != null ? parent.color : null;
    }

    double[] getValues() {
        return values;
    }

    double getMinValue() {
        return Util.min(values);
    }

    double getMaxValue() {
        return Util.max(values);
    }
}
