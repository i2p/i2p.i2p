package org.rrd4j.core;

import java.io.IOException;
import java.util.StringTokenizer;
import java.util.Arrays;

/**
 * <p>Class to represent data source values for the given timestamp. Objects of this
 * class are never created directly (no public constructor is provided). To learn more how
 * to update RRDs, see RRDTool's
 * <a href="../../../../man/rrdupdate.html" target="man">rrdupdate man page</a>.</p>
 * <p>To update a RRD with Rrd4j use the following procedure:</p>
 * <ol>
 * <li>Obtain empty Sample object by calling method {@link org.rrd4j.core.RrdDb#createSample(long)
 * createSample()} on respective {@link RrdDb RrdDb} object.
 * <li>Adjust Sample timestamp if necessary (see {@link #setTime(long) setTime()} method).
 * <li>Supply data source values (see {@link #setValue(String, double) setValue()}).
 * <li>Call Sample's {@link #update() update()} method.
 * </ol>
 * <p>Newly created Sample object contains all data source values set to 'unknown'.
 * You should specify only 'known' data source values. However, if you want to specify
 * 'unknown' values too, use <code>Double.NaN</code>.</p>
 *
 * @author Sasa Markovic
 */
public class Sample {
    private final RrdDb parentDb;
    private long time;
    private final String[] dsNames;
    private final double[] values;

    Sample(RrdDb parentDb, long time) throws IOException {
        this.parentDb = parentDb;
        this.time = time;

        this.dsNames = parentDb.getDsNames();
        values = new double[dsNames.length];
        clearValues();
    }

    private void clearValues() {
        Arrays.fill(values, Double.NaN);
    }

    /**
     * Sets single data source value in the sample.
     *
     * @param dsName Data source name.
     * @param value  Data source value.
     * @return This <code>Sample</code> object
     * @throws java.lang.IllegalArgumentException Thrown if invalid data source name is supplied.
     */
    public Sample setValue(String dsName, double value) {
        for (int i = 0; i < values.length; i++) {
            if (dsNames[i].equals(dsName)) {
                values[i] = value;
                return this;
            }
        }
        throw new IllegalArgumentException("Datasource " + dsName + " not found");
    }

    /**
     * Sets single datasource value using data source index. Data sources are indexed by
     * the order specified during RRD creation (zero-based).
     *
     * @param i     Data source index
     * @param value Data source values
     * @return This <code>Sample</code> object
     * @throws java.lang.IllegalArgumentException Thrown if data source index is invalid.
     */
    public Sample setValue(int i, double value) {
        if (i < values.length) {
            values[i] = value;
            return this;
        }
        throw new IllegalArgumentException("Sample datasource index " + i + " out of bounds");
    }

    /**
     * Sets some (possibly all) data source values in bulk. Data source values are
     * assigned in the order of their definition inside the RRD.
     *
     * @param values Data source values.
     * @return This <code>Sample</code> object
     * @throws java.lang.IllegalArgumentException Thrown if the number of supplied values is zero or greater
     *                                  than the number of data sources defined in the RRD.
     */
    public Sample setValues(double... values) {
        if (values.length <= this.values.length) {
            System.arraycopy(values, 0, this.values, 0, values.length);
            return this;
        }
        throw new IllegalArgumentException("Invalid number of values specified (found " +
                values.length + ", only " + dsNames.length + " allowed)");
    }

    /**
     * Returns all current data source values in the sample.
     *
     * @return Data source values.
     */
    public double[] getValues() {
        return values;
    }

    /**
     * Returns sample timestamp (in seconds, without milliseconds).
     *
     * @return Sample timestamp.
     */
    public long getTime() {
        return time;
    }

    /**
     * Sets sample timestamp. Timestamp should be defined in seconds (without milliseconds).
     *
     * @param time New sample timestamp.
     * @return This <code>Sample</code> object
     */
    public Sample setTime(long time) {
        this.time = time;
        return this;
    }

    /**
     * Returns an array of all data source names. If you try to set value for the data source
     * name not in this array, an exception is thrown.
     *
     * @return Acceptable data source names.
     */
    public String[] getDsNames() {
        return dsNames;
    }

    /**
     * <p>Sets sample timestamp and data source values in a fashion similar to RRDTool.
     * Argument string should be composed in the following way:
     * <code>timestamp:value1:value2:...:valueN</code>.</p>
     * <p>You don't have to supply all datasource values. Unspecified values will be treated
     * as unknowns. To specify unknown value in the argument string, use letter 'U'.</p>
     *
     * @param timeAndValues <p>String made by concatenating sample timestamp with corresponding
     *                      data source values delmited with colons. For example:</p>
     *
     *                      <pre>
     *                      1005234132:12.2:35.6:U:24.5
     *                      NOW:12.2:35.6:U:24.5
     *                      </pre>
     *                      <p>'N' stands for the current timestamp (can be replaced with 'NOW')<p>
     *                      Method will throw an exception if timestamp is invalid (cannot be parsed as Long, and is not 'N'
     *                      or 'NOW'). Datasource value which cannot be parsed as 'double' will be silently set to NaN.</p>
     * @return This <code>Sample</code> object
     * @throws java.lang.IllegalArgumentException Thrown if too many datasource values are supplied
     */
    public Sample set(String timeAndValues) {
        StringTokenizer tokenizer = new StringTokenizer(timeAndValues, ":", false);
        int n = tokenizer.countTokens();
        if (n > values.length + 1) {
            throw new IllegalArgumentException("Invalid number of values specified (found " +
                    values.length + ", " + dsNames.length + " allowed)");
        }
        String timeToken = tokenizer.nextToken();
        try {
            time = Long.parseLong(timeToken);
        }
        catch (NumberFormatException nfe) {
            if ("N".equalsIgnoreCase(timeToken) || "NOW".equalsIgnoreCase(timeToken)) {
                time = Util.getTime();
            }
            else {
                throw new IllegalArgumentException("Invalid sample timestamp: " + timeToken);
            }
        }
        for (int i = 0; tokenizer.hasMoreTokens(); i++) {
            try {
                values[i] = Double.parseDouble(tokenizer.nextToken());
            }
            catch (NumberFormatException nfe) {
                // NOP, value is already set to NaN
            }
        }
        return this;
    }

    /**
     * Stores sample in the corresponding RRD. If the update operation succeeds,
     * all datasource values in the sample will be set to Double.NaN (unknown) values.
     *
     * @throws java.io.IOException Thrown in case of I/O error.
     */
    public void update() throws IOException {
        parentDb.store(this);
        clearValues();
    }

    /**
     * Creates sample with the timestamp and data source values supplied
     * in the argument string and stores sample in the corresponding RRD.
     * This method is just a shortcut for:
     * <pre>
     *     set(timeAndValues);
     *     update();
     * </pre>
     *
     * @param timeAndValues String made by concatenating sample timestamp with corresponding
     *                      data source values delmited with colons. For example:<br>
     *                      <code>1005234132:12.2:35.6:U:24.5</code><br>
     *                      <code>NOW:12.2:35.6:U:24.5</code>
     * @throws java.io.IOException Thrown in case of I/O error.
     */
    public void setAndUpdate(String timeAndValues) throws IOException {
        set(timeAndValues);
        update();
    }

    /**
     * Dumps sample content using the syntax of RRDTool's update command.
     *
     * @return Sample dump.
     */
    public String dump() {
        StringBuilder buffer = new StringBuilder("update \"");
        buffer.append(parentDb.getRrdBackend().getPath()).append("\" ").append(time);
        for (double value : values) {
            buffer.append(':');
            buffer.append(Util.formatDouble(value, "U", false));
        }
        return buffer.toString();
    }

    String getRrdToolCommand() {
        return dump();
    }
}
