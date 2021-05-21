package org.rrd4j.data;

import java.io.IOException;
import java.net.URI;

import org.rrd4j.ConsolFun;
import org.rrd4j.core.FetchData;
import org.rrd4j.core.RrdBackendFactory;
import org.rrd4j.core.RrdDb;

class Def extends Source {
    private final URI rrdUri;
    private final String dsName;
    private final RrdBackendFactory backend;
    private final ConsolFun consolFun;
    private FetchData fetchData;

    Def(String name, FetchData fetchData) {
        this(name, name, fetchData);
    }

    Def(String name, String dsName, FetchData fetchData) {
        this(name,
                fetchData.getRequest().getParentDb().getCanonicalUri(),
                dsName, fetchData.getRequest().getConsolFun(),
                fetchData.getRequest().getParentDb().getRrdBackend().getFactory()
                );
        this.fetchData = fetchData;
    }

    Def(String name, URI rrdUri, String dsName, ConsolFun consolFunc, RrdBackendFactory backend) {
        super(name);
        this.rrdUri = backend.getCanonicalUri(rrdUri);
        this.dsName = dsName;
        this.consolFun = consolFunc;
        this.backend = backend;
    }

    URI getCanonicalUri() throws IOException {
       return rrdUri;
    }

     String getDsName() {
        return dsName;
    }

    ConsolFun getConsolFun() {
        return consolFun;
    }

    RrdBackendFactory getBackend() {
        return backend;
    }

    boolean isCompatibleWith(Def def) throws IOException {
        return getCanonicalUri().equals(def.getCanonicalUri()) &&
                getConsolFun() == def.consolFun &&
                ((backend == null && def.backend == null) ||
                        (backend != null && def.backend != null && backend.equals(def.backend)));
    }

    RrdDb getRrdDb() {
        return fetchData.getRequest().getParentDb();
    }

    void setFetchData(FetchData fetchData) {
        this.fetchData = fetchData;
    }

    long[] getRrdTimestamps() {
        return fetchData.getTimestamps();
    }

    double[] getRrdValues() {
        return fetchData.getValues(dsName);
    }

    long getArchiveEndTime() {
        return fetchData.getArcEndTime();
    }

    long getFetchStep() {
        return fetchData.getStep();
    }

    /* (non-Javadoc)
     * @see org.rrd4j.data.Source#getAggregates(long, long)
     */
    @Override
    @Deprecated
    Aggregates getAggregates(long tStart, long tEnd) {
        long[] t = getRrdTimestamps();
        double[] v = getRrdValues();
        return new Aggregator(t, v).getAggregates(tStart, tEnd);
    }

    /* (non-Javadoc)
     * @see org.rrd4j.data.Source#getPercentile(long, long, double)
     */
    @Override
    @Deprecated
    double getPercentile(long tStart, long tEnd, double percentile) {
        long[] t = getRrdTimestamps();
        double[] v = getRrdValues();
        return new Aggregator(t, v).getPercentile(tStart, tEnd, percentile);
    }

    boolean isLoaded() {
        return fetchData != null;
    }
}
