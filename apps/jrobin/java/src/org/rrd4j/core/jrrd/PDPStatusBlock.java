package org.rrd4j.core.jrrd;

import java.io.IOException;

/**
 * Instances of this class model the primary data point status from an RRD file.
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision: 1.1 $
 */
public class PDPStatusBlock {

    private long offset;
    private long size;
    String lastReading;
    int unknownSeconds;
    double value;
    private static enum pdp_par_en {PDP_unkn_sec_cnt, PDP_val};

    PDPStatusBlock(RRDFile file) throws IOException {

        offset = file.getFilePointer();
        lastReading = file.readString(Constants.LAST_DS_LEN);
        UnivalArray scratch = file.getUnivalArray(10);
        unknownSeconds = (int) scratch.getLong(pdp_par_en.PDP_unkn_sec_cnt);
        value = scratch.getDouble(pdp_par_en.PDP_val);

        size = file.getFilePointer() - offset;
    }

    /**
     * Returns the last reading from the data source.
     *
     * @return the last reading from the data source.
     */
    public String getLastReading() {
        return lastReading;
    }

    /**
     * Returns the current value of the primary data point.
     *
     * @return the current value of the primary data point.
     */
    public double getValue() {
        return value;
    }

    /**
     * Returns the number of seconds of the current primary data point is
     * unknown data.
     *
     * @return the number of seconds of the current primary data point is unknown data.
     */
    public int getUnknownSeconds() {
        return unknownSeconds;
    }

    /**
     * Returns a summary the contents of this PDP status block.
     *
     * @return a summary of the information contained in this PDP status block.
     */
    public String toString() {

        StringBuilder sb = new StringBuilder("[PDPStatus: OFFSET=0x");

        sb.append(Long.toHexString(offset));
        sb.append(", SIZE=0x");
        sb.append(Long.toHexString(size));
        sb.append(", lastReading=");
        sb.append(lastReading);
        sb.append(", unknownSeconds=");
        sb.append(unknownSeconds);
        sb.append(", value=");
        sb.append(value);
        sb.append("]");

        return sb.toString();
    }
}
