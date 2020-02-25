package org.rrd4j.core.jrrd;

import java.io.IOException;
import java.io.PrintStream;
import java.text.NumberFormat;

/**
 * Instances of this class model a data source in an RRD file.
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision: 1.1 $
 */
public class DataSource {

    private static enum ds_param_en { DS_mrhb_cnt, DS_min_val, DS_max_val, DS_cde }

    private final long offset;
    private final long size;
    private final String name;
    private final DataSourceType type;
    private final int minimumHeartbeat;
    private final double minimum;
    private final double maximum;
    // initialized during RRDatabase construction
    private PDPStatusBlock pdpStatusBlock;

    DataSource(RRDFile file) throws IOException {

        offset = file.getFilePointer();
        name = file.readString(Constants.DS_NAM_SIZE);
        type = DataSourceType.valueOf(file.readString(Constants.DST_SIZE).toUpperCase());

        UnivalArray par = file.getUnivalArray(10);
        minimumHeartbeat = (int) par.getLong(ds_param_en.DS_mrhb_cnt);
        minimum = par.getDouble(ds_param_en.DS_min_val);
        maximum = par.getDouble(ds_param_en.DS_max_val);
        size = file.getFilePointer() - offset;
    }

    void loadPDPStatusBlock(RRDFile file) throws IOException {
        pdpStatusBlock = new PDPStatusBlock(file);
    }

    /**
     * Returns the primary data point status block for this data source.
     *
     * @return the primary data point status block for this data source.
     */
    public PDPStatusBlock getPDPStatusBlock() {
        return pdpStatusBlock;
    }

    /**
     * Returns the minimum required heartbeat for this data source.
     *
     * @return the minimum required heartbeat for this data source.
     */
    public int getMinimumHeartbeat() {
        return minimumHeartbeat;
    }

    /**
     * Returns the minimum value input to this data source can have.
     *
     * @return the minimum value input to this data source can have.
     */
    public double getMinimum() {
        return minimum;
    }

    /**
     * Returns the type this data source is.
     *
     * @return the type this data source is.
     * @see DataSourceType
     */
    public DataSourceType getType() {
        return type;
    }

    /**
     * Returns the maximum value input to this data source can have.
     *
     * @return the maximum value input to this data source can have.
     */
    public double getMaximum() {
        return maximum;
    }

    /**
     * Returns the name of this data source.
     *
     * @return the name of this data source.
     */
    public String getName() {
        return name;
    }

    void printInfo(PrintStream s, NumberFormat numberFormat) {

        StringBuilder sb = new StringBuilder("ds[");

        sb.append(name);
        s.print(sb);
        s.print("].type = \"");
        s.print(type);
        s.println("\"");
        s.print(sb);
        s.print("].minimal_heartbeat = ");
        s.println(minimumHeartbeat);
        s.print(sb);
        s.print("].min = ");
        s.println(Double.isNaN(minimum)
                ? "NaN"
                        : numberFormat.format(minimum));
        s.print(sb);
        s.print("].max = ");
        s.println(Double.isNaN(maximum)
                ? "NaN"
                        : numberFormat.format(maximum));
        s.print(sb);
        s.print("].last_ds = ");
        s.println(pdpStatusBlock.lastReading);
        s.print(sb);
        s.print("].value = ");

        double value = pdpStatusBlock.value;

        s.println(Double.isNaN(value)
                ? "NaN"
                        : numberFormat.format(value));
        s.print(sb);
        s.print("].unknown_sec = ");
        s.println(pdpStatusBlock.unknownSeconds);
    }

    void toXml(PrintStream s) {

        s.println("\t<ds>");
        s.print("\t\t<name> ");
        s.print(name);
        s.println(" </name>");
        s.print("\t\t<type> ");
        s.print(type);
        s.println(" </type>");
        s.print("\t\t<minimal_heartbeat> ");
        s.print(minimumHeartbeat);
        s.println(" </minimal_heartbeat>");
        s.print("\t\t<min> ");
        s.print(minimum);
        s.println(" </min>");
        s.print("\t\t<max> ");
        s.print(maximum);
        s.println(" </max>");
        s.println();
        s.println("\t\t<!-- PDP Status -->");
        s.print("\t\t<last_ds> ");
        s.print(pdpStatusBlock.lastReading);
        s.println(" </last_ds>");
        s.print("\t\t<value> ");
        s.print(pdpStatusBlock.value);
        s.println(" </value>");
        s.print("\t\t<unknown_sec> ");
        s.print(pdpStatusBlock.unknownSeconds);
        s.println(" </unknown_sec>");
        s.println("\t</ds>");
        s.println();
    }

    /**
     * Returns a summary the contents of this data source.
     *
     * @return a summary of the information contained in this data source.
     */
    public String toString() {

        StringBuilder sb = new StringBuilder("[DataSource: OFFSET=0x");

        sb.append(Long.toHexString(offset));
        sb.append(", SIZE=0x");
        sb.append(Long.toHexString(size));
        sb.append(", name=");
        sb.append(name);
        sb.append(", type=");
        sb.append(type.toString());
        sb.append(", minHeartbeat=");
        sb.append(minimumHeartbeat);
        sb.append(", min=");
        sb.append(minimum);
        sb.append(", max=");
        sb.append(maximum);
        sb.append("]");
        sb.append("\n\t\t");
        sb.append(pdpStatusBlock.toString());

        return sb.toString();
    }
}
