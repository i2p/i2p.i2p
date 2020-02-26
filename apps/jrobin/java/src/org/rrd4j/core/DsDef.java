package org.rrd4j.core;

import org.rrd4j.DsType;

/**
 * <p>Class to represent single data source definition within the RRD.
 * Datasource definition consists of the following five elements:</p>
 * <ul>
 * <li>data source name
 * <li>data source type
 * <li>heartbeat
 * <li>minimal value
 * <li>maximal value
 * </ul>
 * <p>For the complete explanation of all source definition parameters, see RRDTool's
 * <a href="http://oss.oetiker.ch/rrdtool/doc/rrdcreate.en.html" target="man">rrdcreate man page</a>.</p>
 *
 * @author Sasa Markovic
 */
public class DsDef {
    static final String FORCE_ZEROS_FOR_NANS_SUFFIX = "!";

    private final String dsName;
    private final DsType dsType;
    private final long heartbeat;
    private final double minValue, maxValue;

    /**
     * Creates new data source definition object. This object should be passed as argument
     * to {@link org.rrd4j.core.RrdDef#addDatasource(DsDef) addDatasource()}
     * method of {@link RrdDb RrdDb} object.
     * <p>
     * For the complete explanation of all source definition parameters, see RRDTool's
     * <a href="http://oss.oetiker.ch/rrdtool/doc/rrdcreate.en.html" target="man">rrdcreate man page</a>
     * <p>
     * <b>IMPORTANT NOTE:</b> If datasource name ends with '!', corresponding archives will never
     * store NaNs as datasource values. In that case, NaN datasource values will be silently
     * replaced with zeros by the framework.
     *
     * @param dsName    Data source name.
     * @param dsType    Data source type. Valid values are "COUNTER", "GAUGE", "DERIVE"
     *                  and "ABSOLUTE" (these string constants are conveniently defined in the
     *                  {@link org.rrd4j.DsType} class).
     * @param heartbeat Hearbeat
     * @param minValue  Minimal value. Use <code>Double.NaN</code> if unknown.
     * @param maxValue  Maximal value. Use <code>Double.NaN</code> if unknown.
     */
    public DsDef(String dsName, DsType dsType, long heartbeat, double minValue, double maxValue) {
        if (dsName == null) {
            throw new IllegalArgumentException("Null datasource name specified");
        }
        if (dsName.length() == 0) {
            throw new IllegalArgumentException("Datasource name length equal to zero");
        }
        if (dsType == null) {
            throw new IllegalArgumentException("Null datasource type specified");
        }
        if (heartbeat <= 0) {
            throw new IllegalArgumentException("Invalid heartbeat, must be positive: " + heartbeat);
        }
        if (!Double.isNaN(minValue) && !Double.isNaN(maxValue) && minValue >= maxValue) {
            throw new IllegalArgumentException("Invalid min/max values specified: " +
                    minValue + "/" + maxValue);
        }

        this.dsName = dsName;
        this.dsType = dsType;
        this.heartbeat = heartbeat;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    /**
     * Returns data source name.
     *
     * @return Data source name.
     */
    public String getDsName() {
        return dsName;
    }

    /**
     * Returns source type.
     *
     * @return Source type ("COUNTER", "GAUGE", "DERIVE" or "ABSOLUTE").
     */
    public DsType getDsType() {
        return dsType;
    }

    /**
     * Returns source heartbeat.
     *
     * @return Source heartbeat.
     */
    public long getHeartbeat() {
        return heartbeat;
    }

    /**
     * Returns minimal calculated source value.
     *
     * @return Minimal value.
     */
    public double getMinValue() {
        return minValue;
    }

    /**
     * Returns maximal calculated source value.
     *
     * @return Maximal value.
     */
    public double getMaxValue() {
        return maxValue;
    }

    /**
     * Returns string representing source definition (RRDTool format).
     *
     * @return String containing all data source definition parameters.
     */
    public String dump() {
        return "DS:" + dsName + ":" + dsType + ":" + heartbeat +
                ":" + Util.formatDouble(minValue, "U", false) +
                ":" + Util.formatDouble(maxValue, "U", false);
    }

    /**
     * {@inheritDoc}
     *
     * Checks if two datasource definitions are equal.
     * Source definitions are treated as equal if they have the same source name.
     * It is not possible to create RRD with two equal archive definitions.
     */
    public boolean equals(Object obj) {
        if (obj instanceof DsDef) {
            DsDef dsObj = (DsDef) obj;
            return dsName.equals(dsObj.dsName);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return dsName.hashCode();
    }

    boolean exactlyEqual(DsDef def) {
        return dsName.equals(def.dsName) && dsType == def.dsType &&
                heartbeat == def.heartbeat && Util.equal(minValue, def.minValue) &&
                Util.equal(maxValue, def.maxValue);
    }
}
