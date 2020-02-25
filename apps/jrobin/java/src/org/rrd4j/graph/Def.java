package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;
import org.rrd4j.ConsolFun;
import org.rrd4j.core.RrdBackendFactory;

class Def extends Source {
    private final String rrdPath, dsName;
    private final RrdBackendFactory backend;

    private final ConsolFun consolFun;

    Def(String name, String rrdPath, String dsName, ConsolFun consolFun) {
        this(name, rrdPath, dsName, consolFun, (RrdBackendFactory)null);
    }

    @Deprecated
    Def(String name, String rrdPath, String dsName, ConsolFun consolFun, String backend) {
        super(name);
        this.rrdPath = rrdPath;
        this.dsName = dsName;
        this.consolFun = consolFun;
        this.backend = RrdBackendFactory.getFactory(backend);
    }

    Def(String name, String rrdPath, String dsName, ConsolFun consolFun, RrdBackendFactory backend) {
        super(name);
        this.rrdPath = rrdPath;
        this.dsName = dsName;
        this.consolFun = consolFun;
        this.backend = backend;
    }

    void requestData(DataProcessor dproc) {
        if (backend == null) {
            dproc.addDatasource(name, rrdPath, dsName, consolFun);
        }
        else {
            dproc.addDatasource(name, rrdPath, dsName, consolFun, backend);
        }
    }
}
