package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Paint;
import java.util.Arrays;

import org.rrd4j.data.DataProcessor;

public class ConstantLine extends Line {
    private final double value;

    ConstantLine(double value, Paint color, BasicStroke stroke, SourcedPlotElement parent) {
        super(Double.toString(value), color, stroke, parent);
        this.value = value;
    }

    @Override
    void assignValues(DataProcessor dproc) {
        values = new double[dproc.getTimestamps().length];
        Arrays.fill(values, value);
        if(parent != null) {
            double[] parentValues = parent.getValues();
            for (int i = 0; i < values.length; i++) {
                if (! Double.isNaN(parentValues[i])){
                    values[i] += parentValues[i];
                }
            }
        }
    }

}
