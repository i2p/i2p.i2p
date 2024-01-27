package org.rrd4j.core;

import org.rrd4j.ConsolFun;

import java.io.IOException;
import java.util.Set;

/**
 * Class to represent fetch request. For the complete explanation of all
 * fetch parameters consult RRDTool's
 * <a href="http://oss.oetiker.ch/rrdtool/doc/rrdfetch.en.html" target="man">rrdfetch man page</a>.
 * <p>
 * You cannot create <code>FetchRequest</code> directly (no public constructor
 * is provided). Use {@link org.rrd4j.core.RrdDb#createFetchRequest(ConsolFun, long, long, long)
 * createFetchRequest()} method of your {@link org.rrd4j.core.RrdDb RrdDb} object.
 *
 * @author Sasa Markovic
 */
public class FetchRequest {
    private final RrdDb parentDb;
    private final ConsolFun consolFun;
    private final long fetchStart;
    private final long fetchEnd;
    private final long resolution;
    private String[] filter;

    FetchRequest(RrdDb parentDb, ConsolFun consolFun, long fetchStart, long fetchEnd, long resolution) {
        if (consolFun == null) {
            throw new IllegalArgumentException("Null consolidation function in fetch request");
        }
        if (fetchStart < 0) {
            throw new IllegalArgumentException("Invalid start time in fetch request: " + fetchStart);
        }
        if (fetchEnd < 0) {
            throw new IllegalArgumentException("Invalid end time in fetch request: " + fetchEnd);
        }
        if (fetchStart > fetchEnd) {
            throw new IllegalArgumentException("Invalid start/end time in fetch request: " + fetchStart +
                    " > " + fetchEnd);
        }
        if (resolution <= 0) {
            throw new IllegalArgumentException("Invalid resolution in fetch request: " + resolution);
        }

        this.parentDb = parentDb;
        this.consolFun = consolFun;
        this.fetchStart = fetchStart;
        this.fetchEnd = fetchEnd;
        this.resolution = resolution;
    }

    /**
     * Sets request filter in order to fetch data only for
     * the specified array of datasources (datasource names).
     * If not set (or set to null), fetched data will
     * contain values of all datasources defined in the corresponding RRD.
     * To fetch data only from selected
     * datasources, specify an array of datasource names as method argument.
     *
     * @param filter Array of datasources (datasource names) to fetch data from.
     */
    public void setFilter(String... filter) {
        this.filter = filter;
    }

    /**
     * Sets request filter in order to fetch data only for
     * the specified set of datasources (datasource names).
     * If the filter is not set (or set to null), fetched data will
     * contain values of all datasources defined in the corresponding RRD.
     * To fetch data only from selected
     * datasources, specify a set of datasource names as method argument.
     *
     * @param filter Set of datasource names to fetch data for.
     */
    public void setFilter(Set<String> filter) {
        this.filter = filter.toArray(new String[0]);
    }

    /**
     * Sets request filter in order to fetch data only for
     * a single datasource (datasource name).
     * If not set (or set to null), fetched data will
     * contain values of all datasources defined in the corresponding RRD.
     * To fetch data for a single datasource only,
     * specify an array of datasource names as method argument.
     *
     * @param filter A single datasource (datasource name) to fetch data from.
     */
    public void setFilter(String filter) {
        this.filter = (filter == null) ? null : (new String[]{filter});
    }

    /**
     * Returns request filter. See {@link #setFilter(String...) setFilter()} for
     * complete explanation.
     *
     * @return Request filter (array of datasource names), null if not set.
     */
    public String[] getFilter() {
        return filter;
    }

    /**
     * Returns consolidation function to be used during the fetch process.
     *
     * @return Consolidation function.
     */
    public ConsolFun getConsolFun() {
        return consolFun;
    }

    /**
     * Returns starting timestamp to be used for the fetch request.
     *
     * @return Starting timestamp in seconds.
     */
    public long getFetchStart() {
        return fetchStart;
    }

    /**
     * Returns ending timestamp to be used for the fetch request.
     *
     * @return Ending timestamp in seconds.
     */
    public long getFetchEnd() {
        return fetchEnd;
    }

    /**
     * Returns fetch resolution to be used for the fetch request.
     *
     * @return Fetch resolution in seconds.
     */
    public long getResolution() {
        return resolution;
    }

    /**
     * Dumps the content of fetch request using the syntax of RRDTool's fetch command.
     *
     * @return Fetch request dump.
     */
    public String dump() {
        return "fetch \"" + parentDb.getRrdBackend().getPath() +
                "\" " + consolFun + " --start " + fetchStart + " --end " + fetchEnd +
                (resolution > 1 ? " --resolution " + resolution : "");
    }

    String getRrdToolCommand() {
        return dump();
    }

    /**
     * Returns data from the underlying RRD and puts it in a single
     * {@link org.rrd4j.core.FetchData FetchData} object.
     *
     * @return FetchData object filled with timestamps and datasource values.
     * @throws java.io.IOException Thrown in case of I/O error.
     */
    public FetchData fetchData() throws IOException {
        return parentDb.fetchData(this);
    }

    /**
     * Returns the underlying RrdDb object.
     *
     * @return RrdDb object used to create this FetchRequest object.
     */
    public RrdDb getParentDb() {
        return parentDb;
    }

}
