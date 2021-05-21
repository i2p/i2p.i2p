package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;

import java.net.URI;

import org.rrd4j.ConsolFun;
import org.rrd4j.core.RrdBackendFactory;

class Def extends Source {

    private final URI rrdUri;
    private final String dsName;
    private final RrdBackendFactory backend;

    private final ConsolFun consolFun;

    Def(String name, URI rrdUri, String dsName, ConsolFun consolFun, RrdBackendFactory backend) {
        super(name);
        this.rrdUri = rrdUri;
        this.dsName = dsName;
        this.consolFun = consolFun;
        this.backend = backend;
    }

    void requestData(DataProcessor dproc) {
        dproc.datasource(name, rrdUri, dsName, consolFun, backend);
    }
}
