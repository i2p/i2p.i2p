package org.rrd4j.core.jrrd;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.rrd4j.core.RrdException;

/**
 * Instances of this class model
 * <a href="http://people.ee.ethz.ch/~oetiker/webtools/rrdtool/">Round Robin Database</a>
 * (RRD) files.
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision: 1.1 $
 */
public class RRDatabase implements Closeable {

    final RRDFile rrdFile;

    // RRD file name
    private final String name;
    final Header header;
    private final ArrayList<DataSource> dataSources;
    private final ArrayList<Archive> archives;
    /** Timestamp of last data modification */
    final Date lastUpdate;
    /** Data source name to index */
    private final Map<String, Integer> nameindex;

    /**
     * Creates a database to read from.
     *
     * @param name the filename of the file to read from.
     * @throws java.io.IOException if an I/O error occurs.
     */
    public RRDatabase(String name) throws IOException {
        this(new File(name));
    }

    /**
     * Creates a database to read from.
     *
     * @param file the file to read from.
     * @throws java.io.IOException if an I/O error occurs.
     */
    public RRDatabase(File file) throws IOException {
        /*
         * read the raw data according to the c-structure rrd_t (from rrd source
         * distribution file rrd_format.h)
         */
        name = file.getName();
        rrdFile = new RRDFile(file);
        header = new Header(rrdFile);

        nameindex = new HashMap<>(header.dsCount);

        // Load the data sources
        dataSources = new ArrayList<>(header.dsCount);

        for (int i = 0; i < header.dsCount; i++) {
            DataSource ds = new DataSource(rrdFile);
            nameindex.put(ds.getName(), i);
            dataSources.add(ds);
        }

        // Load the archives
        archives = new ArrayList<>(header.rraCount);

        for (int i = 0; i < header.rraCount; i++) {
            Archive archive = new Archive(this);
            archives.add(archive);
        }

        long last_up = (long) rrdFile.readLong() * 1000;

        /* rrd v >= 3 last_up with us */
        if (header.getVersionAsInt() >= Constants.VERSION_WITH_LAST_UPDATE_SEC) {
            long last_up_usec = rrdFile.readLong();
            last_up += last_up_usec / 1000;
        }
        lastUpdate = new Date(last_up);

        // Load PDPStatus(s)
        for (int i = 0; i < header.dsCount; i++) {
            DataSource ds = dataSources.get(i);
            ds.loadPDPStatusBlock(rrdFile);
        }
        // Load CDPStatus(s)
        for (int i = 0; i < header.rraCount; i++) {
            Archive archive = archives.get(i);
            archive.loadCDPStatusBlocks(rrdFile, header.dsCount);
        }
        // Load current row information for each archive
        for (int i = 0; i < header.rraCount; i++) {
            Archive archive = archives.get(i);
            archive.loadCurrentRow(rrdFile);
        }
        // Now load the data
        for (int i = 0; i < header.rraCount; i++) {
            Archive archive = archives.get(i);
            archive.loadData(rrdFile, header.dsCount);
        }
    }

    /**
     * Returns the <code>Header</code> for this database.
     *
     * @return the <code>Header</code> for this database.
     */
    public Header getHeader() {
        return header;
    }

    /**
     * <p>getDataSourcesName.</p>
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<String> getDataSourcesName() {
        return nameindex.keySet();
    }

    /**
     * Returns the date this database was last updated. To convert this date to
     * the form returned by <code>rrdtool last</code> call Date.getTime() and
     * divide the result by 1000.
     *
     * @return the date this database was last updated.
     */
    public Date getLastUpdate() {
        return lastUpdate;
    }

    /**
     * Returns the <code>DataSource</code> at the specified position in this database.
     *
     * @param index index of <code>DataSource</code> to return.
     * @return the <code>DataSource</code> at the specified position in this database
     */
    public DataSource getDataSource(int index) {
        return dataSources.get(index);
    }

    /**
     * Returns an iterator over the data sources in this database in proper sequence.
     *
     * @return an iterator over the data sources in this database in proper sequence.
     */
    public Iterator<DataSource> getDataSources() {
        return dataSources.iterator();
    }

    /**
     * Returns the <code>Archive</code> at the specified position in this database.
     *
     * @param index index of <code>Archive</code> to return.
     * @return the <code>Archive</code> at the specified position in this database.
     */
    public Archive getArchive(int index) {
        return archives.get(index);
    }

    /**
     * <p>getArchive.</p>
     *
     * @param name a {@link java.lang.String} object.
     * @return a {@link org.rrd4j.core.jrrd.Archive} object.
     */
    public Archive getArchive(String name) {
        return archives.get(nameindex.get(name));
    }

    /**
     * Returns an iterator over the archives in this database in proper sequence.
     *
     * @return an iterator over the archives in this database in proper sequence.
     */
    public Iterator<Archive> getArchives() {
        return archives.iterator();
    }

    /**
     * Returns the number of archives in this database.
     *
     * @return the number of archives in this database.
     */
    public int getNumArchives() {
        return header.rraCount;
    }

    /**
     * Returns an iterator over the archives in this database of the given type
     * in proper sequence.
     *
     * @param type the consolidation function that should have been applied to
     *             the data.
     * @return an iterator over the archives in this database of the given type
     *         in proper sequence.
     */
    public Iterator<Archive> getArchives(ConsolidationFunctionType type) {
        return getArchiveList(type).iterator();
    }

    ArrayList<Archive> getArchiveList(ConsolidationFunctionType type) {

        ArrayList<Archive> subset = new ArrayList<>();

        for (Archive archive : archives) {
            if (archive.getType().equals(type)) {
                subset.add(archive);
            }
        }

        return subset;
    }

    /**
     * Closes this database stream and releases any associated system resources.
     *
     * @throws java.io.IOException if an I/O error occurs.
     */
    public void close() throws IOException {
        rrdFile.close();
    }

    /**
     * Outputs the header information of the database to the given print stream
     * using the default number format. The default format for <code>double</code>
     * is 0.0000000000E0.
     *
     * @param s the PrintStream to print the header information to.
     */
    public void printInfo(PrintStream s) {

        NumberFormat numberFormat = new DecimalFormat("0.0000000000E0");

        printInfo(s, numberFormat);
    }

    /**
     * Returns data from the database corresponding to the given consolidation
     * function and a step size of 1.
     *
     * @param type the consolidation function that should have been applied to
     *             the data.
     * @return the raw data.
     * @throws java.lang.IllegalArgumentException if there was a problem locating a data archive with
     *                                  the requested consolidation function.
     * @throws java.io.IOException              if there was a problem reading data from the database.
     */
    public DataChunk getData(ConsolidationFunctionType type) throws IOException {
        Calendar endCal = Calendar.getInstance();

        endCal.set(Calendar.MILLISECOND, 0);

        Calendar startCal = (Calendar) endCal.clone();

        startCal.add(Calendar.DATE, -1);

        return getData(type, startCal.getTime(), endCal.getTime(), 1L);
    }

    /**
     * Returns data from the database corresponding to the given consolidation
     * function.
     *
     * @param type the consolidation function that should have been applied to
     *             the data.
     * @param step the step size to use.
     * @return the raw data.
     * @throws java.lang.IllegalArgumentException if there was a problem locating a data archive with
     *                                  the requested consolidation function.
     * @throws java.io.IOException              if there was a problem reading data from the database.
     * @param startDate a {@link java.util.Date} object.
     * @param endDate a {@link java.util.Date} object.
     */
    public DataChunk getData(ConsolidationFunctionType type, Date startDate, Date endDate, long step)
            throws IOException {
        long end = endDate.getTime() / 1000;
        long start = startDate.getTime() / 1000;
        return getData(type, start, end, step);
    }

    /**
     * <p>getData.</p>
     *
     * @param type a {@link org.rrd4j.core.jrrd.ConsolidationFunctionType} object.
     * @param startTime seconds since epoch
     * @param endTime seconds since epoch
     * @param stepSeconds in seconds
     * @return a {@link org.rrd4j.core.jrrd.DataChunk} object.
     * @throws java.io.IOException if any.
     */
    public DataChunk getData(ConsolidationFunctionType type, long startTime, long endTime, long stepSeconds)
            throws IOException {

        ArrayList<Archive> possibleArchives = getArchiveList(type);

        if (possibleArchives.size() == 0) {
            throw new IllegalArgumentException("Database does not contain an Archive of consolidation function type "
                    + type);
        }

        Archive archive = findBestArchive(startTime, endTime, stepSeconds, possibleArchives);

        if (archive == null) {
            throw new RrdException("No matching archive found");
        }

        // Tune the parameters
        stepSeconds = (long) header.pdpStep * archive.pdpCount;
        startTime -= startTime % stepSeconds;

        if (endTime % stepSeconds != 0) {
            endTime += stepSeconds - endTime % stepSeconds;
        }

        int rows = (int) ((endTime - startTime) / stepSeconds + 1);

        // Find start and end offsets
        // This is terrible - some of this should be encapsulated in Archive - CT.
        long lastUpdateLong = lastUpdate.getTime() / 1000;
        long archiveEndTime = lastUpdateLong - (lastUpdateLong % stepSeconds);
        long archiveStartTime = archiveEndTime - (stepSeconds * (archive.rowCount - 1));
        int startOffset = (int) ((startTime - archiveStartTime) / stepSeconds);
        int endOffset = (int) ((archiveEndTime - endTime) / stepSeconds);

        DataChunk chunk = new DataChunk(nameindex, startTime, startOffset, endOffset,
                stepSeconds, header.dsCount, rows);

        archive.loadData(chunk);

        return chunk;
    }

    /*
     * This is almost a verbatim copy of the original C code by Tobias Oetiker.
     * I need to put more of a Java style on it - CT
     */
    private Archive findBestArchive(long start, long end, long step,
            ArrayList<Archive> archives) {

        Archive archive = null;
        Archive bestFullArchive = null;
        Archive bestPartialArchive = null;
        long lastUpdateLong = lastUpdate.getTime() / 1000;
        int firstPart = 1;
        int firstFull = 1;
        long bestMatch = 0;
        long bestStepDiff = 0;
        long tmpStepDiff;

        for (Archive archive1 : archives) {
            archive = archive1;

            long calEnd = lastUpdateLong
                    - (lastUpdateLong
                            % (archive.pdpCount * header.pdpStep));
            long calStart = calEnd
                    - (archive.pdpCount * archive.rowCount
                            * header.pdpStep);
            long fullMatch = end - start;

            if ((calEnd >= end) && (calStart < start)) {    // Best full match
                tmpStepDiff = Math.abs(step - (header.pdpStep * archive.pdpCount));

                if ((firstFull != 0) || (tmpStepDiff < bestStepDiff)) {
                    firstFull = 0;
                    bestStepDiff = tmpStepDiff;
                    bestFullArchive = archive;
                }
            }
            else {                                        // Best partial match
                long tmpMatch = fullMatch;

                if (calStart > start) {
                    tmpMatch -= calStart - start;
                }

                if (calEnd < end) {
                    tmpMatch -= end - calEnd;
                }

                if ((firstPart != 0) || (bestMatch < tmpMatch)) {
                    firstPart = 0;
                    bestMatch = tmpMatch;
                    bestPartialArchive = archive;
                }
            }
        }

        // See how the matching went
        // optimize this
        if (firstFull == 0) {
            archive = bestFullArchive;
        }
        else if (firstPart == 0) {
            archive = bestPartialArchive;
        }

        return archive;
    }

    /**
     * Outputs the header information of the database to the given print stream
     * using the given number format. The format is almost identical to that
     * produced by
     * <a href="http://people.ee.ethz.ch/~oetiker/webtools/rrdtool/manual/rrdinfo.html">rrdtool info</a>
     *
     * @param s            the PrintStream to print the header information to.
     * @param numberFormat the format to print <code>double</code>s as.
     */
    public void printInfo(PrintStream s, NumberFormat numberFormat) {

        s.print("filename = \"");
        s.print(name);
        s.println("\"");
        s.print("rrd_version = \"");
        s.print(header.version);
        s.println("\"");
        s.print("step = ");
        s.println(header.pdpStep);
        s.print("last_update = ");
        s.println(lastUpdate.getTime() / 1000);

        for (DataSource ds : dataSources) {
            ds.printInfo(s, numberFormat);
        }

        int index = 0;

        for (Archive archive : archives) {
            archive.printInfo(s, numberFormat, index++);
        }
    }

    /**
     * Outputs the content of the database to the given print stream
     * as a stream of XML. The XML format is almost identical to that produced by
     * <a href="http://people.ee.ethz.ch/~oetiker/webtools/rrdtool/manual/rrddump.html">rrdtool dump</a>
     * <p>
     * A flush is issued at the end of the XML generation, so auto flush of the PrintStream can be set to false
     *
     * @param s the PrintStream to send the XML to.
     */
    public void toXml(PrintStream s) {

        s.println("<!--");
        s.println("  Round Robin RRDatabase Dump ");
        s.println("  Generated by jRRD <ciaran@codeloop.com>");
        s.println("-->");
        s.println("<rrd>");
        s.print("\t<version> ");
        s.print(header.version);
        s.println(" </version>");
        s.print("\t<step> ");
        s.print(header.pdpStep);
        s.println(" </step> <!-- Seconds -->");
        s.print("\t<lastupdate> ");
        s.print(lastUpdate.getTime() / 1000);
        s.print(" </lastupdate> <!-- ");
        s.print(lastUpdate);
        s.println(" -->");
        s.println();

        for (int i = 0; i < header.dsCount; i++) {
            DataSource ds = dataSources.get(i);

            ds.toXml(s);
        }

        s.println("<!-- Round Robin Archives -->");

        for (int i = 0; i < header.rraCount; i++) {
            Archive archive = archives.get(i);

            archive.toXml(s);
        }

        s.println("</rrd>");
        s.flush();
    }

    /**
     * Returns a summary the contents of this database.
     *
     * @return a summary of the information contained in this database.
     */
    public String toString() {

        String endianness;
        if(rrdFile.isBigEndian())
            endianness = "Big";
        else
            endianness = "Little";


        StringBuilder sb = new StringBuilder(endianness + " endian" + ", " + rrdFile.getBits() + " bits\n");

        sb.append(header.toString());

        sb.append(", lastupdate: ");
        sb.append(lastUpdate.getTime() / 1000);


        for (DataSource ds : dataSources) {
            sb.append("\n\t");
            sb.append(ds.toString());
        }

        for (Archive archive : archives) {
            sb.append("\n\t");
            sb.append(archive.toString());
        }

        return sb.toString();
    }
}
