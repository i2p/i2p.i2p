package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;
import org.rrd4j.data.Plottable;

class PDef extends Source {
    private Plottable plottable;

    PDef(String name, Plottable plottable) {
        super(name);
        this.plottable = plottable;
    }

    void requestData(DataProcessor dproc) {
        dproc.addDatasource(name, plottable);
    }
}
