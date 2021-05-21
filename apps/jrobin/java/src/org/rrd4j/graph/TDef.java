package org.rrd4j.graph;

import org.rrd4j.core.FetchData;
import org.rrd4j.data.DataProcessor;

/** @author Mathias Bogaert */
class TDef extends Source {
    private final FetchData fetchData;
    private final String dsName;

    TDef(String name, String dsName, FetchData fetchData) {
        super(name);
        this.dsName = dsName;
        this.fetchData = fetchData;
    }

    @Override
    void requestData(DataProcessor dproc) {
        dproc.datasource(name, dsName, fetchData);
    }

}
