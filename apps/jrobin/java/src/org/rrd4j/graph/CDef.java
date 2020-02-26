package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;

class CDef extends Source {
    private final String rpnExpression;

    CDef(String name, String rpnExpression) {
        super(name);
        this.rpnExpression = rpnExpression;
    }

    void requestData(DataProcessor dproc) {
        dproc.addDatasource(name, rpnExpression);
    }
}
