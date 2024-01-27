package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;
import org.rrd4j.data.IPlottable;

class PDef extends Source {
    private final IPlottable plottable;

    PDef(String name, IPlottable plottable) {
        super(name);
        this.plottable = plottable;
    }

    void requestData(DataProcessor dproc) {
        dproc.datasource(name, plottable);
    }
}
