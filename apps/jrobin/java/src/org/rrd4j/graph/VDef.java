package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;
import org.rrd4j.data.Variable;

class VDef extends Source {
    private final String defName;
    private final Variable var;

    VDef(String name, String defName, Variable var) {
        super(name);
        this.defName = defName;
        this.var = var;
    }

    void requestData(DataProcessor dproc) {
        dproc.addDatasource(name, defName, var);
    }

}
