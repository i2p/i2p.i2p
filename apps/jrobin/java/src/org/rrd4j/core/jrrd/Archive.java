package org.rrd4j.core.jrrd;

import java.io.PrintStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Instances of this class model an archive section of an RRD file.
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision: 1.1 $
 */
public class Archive {

    private enum rra_par_en {RRA_cdp_xff_val, RRA_hw_alpha}

    final RRDatabase db;
    /** Header offset within file in bytes */
    final long headerOffset;
    /** Header size in bytes */
    private final long headerSize;
    /** Data offset within file in bytes */
    long dataOffset;
    private final ConsolidationFunctionType type;
    /** Data row count */
    final int rowCount;
    final int pdpCount;
    final double xff;

    /// Following fields are initialized during RRDatabase construction
    /// and in fact immutable

    /** Consolitation data points */
    List<CDPStatusBlock> cdpStatusBlocks;
    /** Row for last modification time of database */
    int currentRow;

    /** Cached content */
    private double[][] values;

    Archive(RRDatabase db) {

        this.db = db;

        RRDFile file = db.rrdFile;

        headerOffset = file.getFilePointer();
        type = ConsolidationFunctionType.valueOf(file.readString(Constants.CF_NAM_SIZE).toUpperCase());
        file.align(file.getBits() / 8);
        rowCount = file.readLong();
        pdpCount = file.readLong();

        UnivalArray par = file.getUnivalArray(10);
        xff = par.getDouble(rra_par_en.RRA_cdp_xff_val);

        headerSize = file.getFilePointer() - headerOffset;
    }

    /**
     * Returns the type of function used to calculate the consolidated data point.
     *
     * @return the type of function used to calculate the consolidated data point.
     */
    public ConsolidationFunctionType getType() {
        return type;
    }

    void loadCDPStatusBlocks(RRDFile file, int numBlocks) {

        cdpStatusBlocks = new ArrayList<>();

        for (int i = 0; i < numBlocks; i++) {
            cdpStatusBlocks.add(new CDPStatusBlock(file));
        }
    }

    /**
     * Returns the <code>CDPStatusBlock</code> at the specified position in this archive.
     *
     * @param index index of <code>CDPStatusBlock</code> to return.
     * @return the <code>CDPStatusBlock</code> at the specified position in this archive.
     */
    public CDPStatusBlock getCDPStatusBlock(int index) {
        return cdpStatusBlocks.get(index);
    }

    /**
     * Returns an iterator over the CDP status blocks in this archive in proper sequence.
     *
     * @return an iterator over the CDP status blocks in this archive in proper sequence.
     * @see CDPStatusBlock
     */
    public Iterator<CDPStatusBlock> getCDPStatusBlocks() {
        return cdpStatusBlocks.iterator();
    }

    void loadCurrentRow(RRDFile file) {
        currentRow = file.readLong();
    }

    void loadData(RRDFile file, int dsCount) {

        dataOffset = file.getFilePointer();

        // Skip over the data to position ourselves at the start of the next archive
        file.skipBytes(Constants.SIZE_OF_DOUBLE * rowCount * dsCount);
    }

    void loadData(DataChunk chunk) {

        long rowIndexPointer;

        if (chunk.startOffset < 0) {
            rowIndexPointer = currentRow + 1L;
        }
        else {
            rowIndexPointer = currentRow + chunk.startOffset + 1L;
        }

        if (rowIndexPointer < rowCount) {
            db.rrdFile.seek((dataOffset + (chunk.dsCount * rowIndexPointer * Constants.SIZE_OF_DOUBLE)));
        } else {
            // Safety net: prevent from reading random portions of file
            // if something went wrong
            db.rrdFile.seekToEndOfFile();
        }

        double[][] data = chunk.data;

        /*
         * This is also terrible - cleanup - CT
         */
        int row = 0;
        for (int i = chunk.startOffset; i < rowCount - chunk.endOffset; i++, row++) {
            if (i < 0) {                   // no valid data yet
                Arrays.fill(data[row], Double.NaN);
            }
            else if (i >= rowCount) {    // past valid data area
                Arrays.fill(data[row], Double.NaN);
            }
            else {                       // inside the valid are but the pointer has to be wrapped
                if (rowIndexPointer >= rowCount) {
                    rowIndexPointer -= rowCount;

                    db.rrdFile.seek(dataOffset + (chunk.dsCount * rowIndexPointer * Constants.SIZE_OF_DOUBLE));
                }

                for (int ii = 0; ii < chunk.dsCount; ii++) {
                    data[row][ii] = db.rrdFile.readDouble();
                }

                rowIndexPointer++;
            }
        }
    }

    void printInfo(PrintStream s, NumberFormat numberFormat, int index) {

        StringBuilder sb = new StringBuilder("rra[");

        sb.append(index);
        s.print(sb);
        s.print("].cf = \"");
        s.print(type);
        s.println("\"");
        s.print(sb);
        s.print("].rows = ");
        s.println(rowCount);
        s.print(sb);
        s.print("].pdp_per_row = ");
        s.println(pdpCount);
        s.print(sb);
        s.print("].xff = ");
        s.println(xff);
        sb.append("].cdp_prep[");

        int cdpIndex = 0;

        for (CDPStatusBlock cdp : cdpStatusBlocks) {
            s.print(sb);
            s.print(cdpIndex);
            s.print("].value = ");

            double value = cdp.value;

            s.println(Double.isNaN(value) ? "NaN" : numberFormat.format(value));
            s.print(sb);
            s.print(cdpIndex++);
            s.print("].unknown_datapoints = ");
            s.println(cdp.unknownDatapoints);
        }
    }

    void toXml(PrintStream s) {
        s.println("\t<rra>");
        s.print("\t\t<cf> ");
        s.print(type);
        s.println(" </cf>");
        s.print("\t\t<pdp_per_row> ");
        s.print(pdpCount);
        s.print(" </pdp_per_row> <!-- ");
        s.print(db.header.pdpStep * pdpCount);
        s.println(" seconds -->");
        s.print("\t\t<xff> ");
        s.print(xff);
        s.println(" </xff>");
        s.println();
        s.println("\t\t<cdp_prep>");

        for (CDPStatusBlock cdpStatusBlock : cdpStatusBlocks) {
            cdpStatusBlock.toXml(s);
        }

        s.println("\t\t</cdp_prep>");
        s.println("\t\t<database>");

        long timer = -(rowCount - 1);
        int counter = 0;
        int row = currentRow;

        db.rrdFile.seek(dataOffset + (row + 1) * db.header.dsCount * Constants.SIZE_OF_DOUBLE);

        long lastUpdate = db.lastUpdate.getTime() / 1000;
        int pdpStep = db.header.pdpStep;
        NumberFormat numberFormat = new DecimalFormat("0.0000000000E0", DecimalFormatSymbols.getInstance(Locale.US));
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");

        while (counter++ < rowCount) {
            row++;

            if (row == rowCount) {
                row = 0;

                db.rrdFile.seek(dataOffset);
            }

            long now = (lastUpdate - lastUpdate % (pdpCount * pdpStep))
                    + (timer * pdpCount * pdpStep);

            timer++;

            s.print("\t\t\t<!-- ");
            s.print(dateFormat.format(new Date(now * 1000)));
            s.print(" / ");
            s.print(now);
            s.print(" --> ");

            s.println("<row>");
            for (int col = 0; col < db.header.dsCount; col++) {
                s.print("<v> ");

                double value = db.rrdFile.readDouble();

                // NumberFormat doesn't know how to handle NaN
                if (Double.isNaN(value)) {
                    s.print("NaN");
                }
                else {
                    s.print(numberFormat.format(value));
                }

                s.print(" </v>");
            }

            s.println("</row>");
        }

        s.println("\t\t</database>");
        s.println("\t</rra>");
    }

    /**
     * <p>Getter for the field <code>values</code>.</p>
     *
     * @return an array of double.
     */
    public double[][] getValues() {
        if (values != null) {
            return values;
        }
        values = new double[db.header.dsCount][rowCount];
        int row = currentRow;
        db.rrdFile.seek(dataOffset + (row + 1) * db.header.dsCount * Constants.SIZE_OF_DOUBLE);
        for (int counter = 0; counter < rowCount; counter++) {
            row++;
            if (row == rowCount) {
                row = 0;
                db.rrdFile.seek(dataOffset);
            }
            for (int col = 0; col < db.header.dsCount; col++) {
                double value = db.rrdFile.readDouble();
                values[col][counter] = value;
            }
        }
        return values;
    }

    /**
     * Returns the number of primary data points required for a consolidated
     * data point in this archive.
     *
     * @return the number of primary data points required for a consolidated
     *         data point in this archive.
     */
    public int getPdpCount() {
        return pdpCount;
    }

    /**
     * Returns the number of entries in this archive.
     *
     * @return the number of entries in this archive.
     */
    public int getRowCount() {
        return rowCount;
    }

    /**
     * Returns the X-Files Factor for this archive.
     *
     * @return the X-Files Factor for this archive.
     */
    public double getXff() {
        return xff;
    }

    /**
     * Returns a summary the contents of this archive.
     *
     * @return a summary of the information contained in this archive.
     */
    public String toString() {

        StringBuilder sb = new StringBuilder("[Archive: OFFSET=0x");

        sb.append(Long.toHexString(headerOffset))
          .append(", SIZE=0x")
          .append(Long.toHexString(headerSize))
          .append(", type=")
          .append(type)
          .append(", rowCount=")
          .append(rowCount)
          .append(", pdpCount=")
          .append(pdpCount)
          .append(", xff=")
          .append(xff)
          .append(", currentRow=")
          .append(currentRow)
          .append("]");

        for(CDPStatusBlock cdp: cdpStatusBlocks) {
            sb.append("\n\t\t");
            sb.append(cdp.toString());
        }

        return sb.toString();
    }
}
