package org.rrd4j.graph;

class ValueAxisSetting {
    final double gridStep;
    final int labelFactor;

    ValueAxisSetting(double gridStep, int labelFactor) {
        this.gridStep = gridStep;
        this.labelFactor = labelFactor;
    }
}
