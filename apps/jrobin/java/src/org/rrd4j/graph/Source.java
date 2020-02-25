package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;

abstract class Source {
    final String name;

    Source(String name) {
        this.name = name;
    }

    abstract void requestData(DataProcessor dproc);
}
