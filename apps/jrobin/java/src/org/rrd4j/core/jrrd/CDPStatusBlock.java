package org.rrd4j.core.jrrd;

import java.io.PrintStream;

/**
 * Instances of this class model the consolidation data point status from an RRD file.
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision: 1.1 $
 */
public class CDPStatusBlock {

    private enum cdp_par_en {
        CDP_val, CDP_unkn_pdp_cnt, CDP_hw_intercept, CDP_hw_last_intercept, CDP_hw_slope, 
        CDP_hw_last_slope, CDP_null_count,
        CDP_last_null_count, CDP_primary_val, CDP_secondary_val
    }

    /** Byte offset within file */
    final long offset;
    /** Size of block in bytes */
    final long size;
    final int unknownDatapoints;
    final double value;

    final double secondary_value;
    final double primary_value;

    CDPStatusBlock(RRDFile file) {
        //Should read MAX_CDP_PAR_EN = 10
        //Size should be 0x50
        offset = file.getFilePointer();
        UnivalArray scratch = file.getUnivalArray(10);
        value = scratch.getDouble(cdp_par_en.CDP_val);
        unknownDatapoints = (int) scratch.getDouble(cdp_par_en.CDP_unkn_pdp_cnt);
        primary_value = scratch.getDouble(cdp_par_en.CDP_primary_val);
        secondary_value = scratch.getDouble(cdp_par_en.CDP_secondary_val);

        size = file.getFilePointer() - offset;
    }

    /**
     * Returns the number of unknown primary data points that were integrated.
     *
     * @return the number of unknown primary data points that were integrated.
     */
    public int getUnknownDatapoints() {
        return unknownDatapoints;
    }

    /**
     * Returns the value of this consolidated data point.
     *
     * @return the value of this consolidated data point.
     */
    public double getValue() {
        return value;
    }

    void toXml(PrintStream s) {
        s.print("\t\t\t<ds><value> ");
        s.print(value);
        s.print(" </value>  <unknown_datapoints> ");
        s.print(unknownDatapoints);
        s.println(" </unknown_datapoints></ds>");
    }

    /**
     * Returns a summary the contents of this CDP status block.
     *
     * @return a summary of the information contained in the CDP status block.
     */
    public String toString() {

        StringBuilder sb = new StringBuilder("[CDPStatusBlock: OFFSET=0x");

        sb.append(Long.toHexString(offset));
        sb.append(", SIZE=0x");
        sb.append(Long.toHexString(size));
        sb.append(", unknownDatapoints=");
        sb.append(unknownDatapoints);
        sb.append(", value=");
        sb.append(value);
        sb.append(", primaryValue=");
        sb.append(primary_value);
        sb.append(", secondaryValue=");
        sb.append(secondary_value);
        sb.append("]");

        return sb.toString();
    }
}
