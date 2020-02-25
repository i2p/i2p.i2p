package org.rrd4j.core;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;

/**
 * <p>Class to represent definition of new Round Robin Database (RRD).
 * Object of this class is used to create
 * new RRD from scratch - pass its reference as a <code>RrdDb</code> constructor
 * argument (see documentation for {@link org.rrd4j.core.RrdDb RrdDb} class). <code>RrdDef</code>
 * object <b>does not</b> actually create new RRD. It just holds all necessary
 * information which will be used during the actual creation process.</p>
 *
 * <p>RRD definition (RrdDef object) consists of the following elements:</p>
 *
 * <ul>
 * <li> path to RRD that will be created
 * <li> starting timestamp
 * <li> step
 * <li> version, 1 for linear disposition of archives, 2 for matrix disposition
 * <li> one or more datasource definitions
 * <li> one or more archive definitions
 * </ul>
 * <p>RrdDef provides API to set all these elements. For the complete explanation of all
 * RRD definition parameters, see RRDTool's
 * <a href="../../../../man/rrdcreate.html" target="man">rrdcreate man page</a>.</p>
 *
 * @author Sasa Markovic
 */
public class RrdDef {
    /**
     * Default RRD step to be used if not specified in constructor (300 seconds).
     */
    public static final long DEFAULT_STEP = 300L;
    
    /**
     * If not specified in constructor, starting timestamp will be set to the
     * current timestamp plus DEFAULT_INITIAL_SHIFT seconds (-10).
     */
    public static final long DEFAULT_INITIAL_SHIFT = -10L;

    /** Constant <code>DEFAULTVERSION=2</code> */
    public static final int DEFAULTVERSION = 2;

    private URI uri;
    private long startTime = Util.getTime() + DEFAULT_INITIAL_SHIFT;
    private long step = DEFAULT_STEP;
    private int version = DEFAULTVERSION;

    private List<DsDef> dsDefs = new ArrayList<>();
    private List<ArcDef> arcDefs = new ArrayList<>();

    /**
     * <p>Creates new RRD definition object with the given path.
     * When this object is passed to
     * <code>RrdDb</code> constructor, new RRD will be created using the
     * specified path.</p>
     * <p>The will be transformed internally to an URI using the default backend factory.</p>
     *
     * @param rrdpath Path to new RRD.
     */
    public RrdDef(String rrdpath) {
        if (rrdpath == null || rrdpath.length() == 0) {
            throw new IllegalArgumentException("No path specified");
        }
        this.uri = RrdBackendFactory.buildGenericUri(rrdpath);
    }

    /**
     * Creates new RRD definition object with the given path.
     * When this object is passed to
     * <code>RrdDb</code> constructor, new RRD will be created using the
     * specified path.
     *
     * @param uri URI to the new RRD.
     */
    public RrdDef(URI uri) {
        this.uri = uri;
    }

    /**
     * <p>Creates new RRD definition object with the given path and step.</p>
     * <p>The will be transformed internally to an URI using the default backend factory.</p>
     *
     * @param path URI to new RRD.
     * @param step RRD step.
     */
    public RrdDef(String path, long step) {
        this(path);
        if (step <= 0) {
            throw new IllegalArgumentException("Invalid RRD step specified: " + step);
        }
        this.step = step;
    }

    /**
     * Creates new RRD definition object with the given path and step.
     *
     * @param uri  URI to new RRD.
     * @param step RRD step.
     */
    public RrdDef(URI uri, long step) {
        this(uri);
        if (step <= 0) {
            throw new IllegalArgumentException("Invalid RRD step specified: " + step);
        }
        this.step = step;
    }

    /**
     * <p>Creates new RRD definition object with the given path, starting timestamp
     * and step.</p>
     * <p>The will be transformed internally to an URI using the default backend factory.</p>
     *
     * @param path      Path to new RRD.
     * @param startTime RRD starting timestamp.
     * @param step      RRD step.
     */
    public RrdDef(String path, long startTime, long step) {
        this(path, step);
        if (startTime < 0) {
            throw new IllegalArgumentException("Invalid RRD start time specified: " + startTime);
        }
        this.startTime = startTime;
    }

    /**
     * Creates new RRD definition object with the given path, starting timestamp
     * and step.
     *
     * @param uri       URI to new RRD.
     * @param startTime RRD starting timestamp.
     * @param step      RRD step.
     */
    public RrdDef(URI uri, long startTime, long step) {
        this(uri, step);
        if (startTime < 0) {
            throw new IllegalArgumentException("Invalid RRD start time specified: " + startTime);
        }
        this.startTime = startTime;
    }

    /**
     * <p>Creates new RRD definition object with the given path, starting timestamp,
     * step and version.</p>
     * <p>The will be transformed internally to an URI using the default backend factory.</p>
     *
     * @param path Path to new RRD.
     * @param startTime RRD starting timestamp.
     * @param step      RRD step.
     * @param version   RRD's file version.
     */
    public RrdDef(String path, long startTime, long step, int version) {
        this(path, startTime, step);
        if(startTime < 0) {
            throw new IllegalArgumentException("Invalid RRD start time specified: " + startTime);
        }
        this.version = version;
    }

    /**
     * Creates new RRD definition object with the given path, starting timestamp,
     * step and version.
     *
     * @param uri       URI to new RRD.
     * @param startTime RRD starting timestamp.
     * @param step      RRD step.
     * @param version   RRD's file version.
     */
    public RrdDef(URI uri, long startTime, long step, int version) {
        this(uri, startTime, step);
        if(startTime < 0) {
            throw new IllegalArgumentException("Invalid RRD start time specified: " + startTime);
        }
        this.version = version;
    }

    /**
     * Returns path for the new RRD. It's extracted from the URI. If it's an opaque URI, it return the scheme specific part.
     *
     * @return path to the new RRD which should be created
     */
    public String getPath() {
        if (uri.isOpaque()) {
            return uri.getSchemeSpecificPart();
        } else {
            return uri.getPath();
        }
    }

    /**
     * Returns URI for the new RRD
     *
     * @return URI to the new RRD which should be created
     */
    public URI getUri() {
        return uri;
    }

    /**
     * Returns starting time stamp for the RRD that should be created.
     *
     * @return RRD starting time stamp
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * Returns time step for the RRD that will be created.
     *
     * @return RRD step
     */
    public long getStep() {
        return step;
    }

    /**
     * Returns the RRD file version
     *
     * @return the version
     */
    public int getVersion() {
        return version;
    }

    /**
     * <p>Sets path to RRD.</p>
     * <p>The will be transformed internally to an URI using the default backend factory.</p>
     *
     * @param path path to new RRD.
     */
    public void setPath(String path) {
        this.uri = RrdBackendFactory.getDefaultFactory().getUri(path);
    }

    /**
     * Sets URI to RRD.
     *
     * @param uri URI to new RRD.
     */
    public void setPath(URI uri) {
        this.uri = uri;
    }

    /**
     * Sets RRD's starting timestamp.
     *
     * @param startTime Starting timestamp.
     */
    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    /**
     * Sets RRD's starting timestamp.
     *
     * @param date starting date
     */
    public void setStartTime(Date date) {
        this.startTime = Util.getTimestamp(date);
    }

    /**
     * Sets RRD's starting timestamp.
     *
     * @param gc starting date
     */
    public void setStartTime(Calendar gc) {
        this.startTime = Util.getTimestamp(gc);
    }

    /**
     * Sets RRD's time step.
     *
     * @param step RRD time step.
     */
    public void setStep(long step) {
        this.step = step;
    }

    /**
     * Sets RRD's file version.
     *
     * @param version the version to set
     */
    public void setVersion(int version) {
        this.version = version;
    }

    /**
     * Adds single datasource definition represented with object of class <code>DsDef</code>.
     *
     * @param dsDef Datasource definition.
     */
    public void addDatasource(DsDef dsDef) {
        if (dsDefs.contains(dsDef)) {
            throw new IllegalArgumentException("Datasource already defined: " + dsDef.dump());
        }
        dsDefs.add(dsDef);
    }

    /**
     * <p>Adds single datasource to RRD definition by specifying its data source name, source type,
     * heartbeat, minimal and maximal value. For the complete explanation of all data
     * source definition parameters see RRDTool's
     * <a href="../../../../man/rrdcreate.html" target="man">rrdcreate man page</a>.</p>
     * <p><b>IMPORTANT NOTE:</b> If datasource name ends with '!', corresponding archives will never
     * store NaNs as datasource values. In that case, NaN datasource values will be silently
     * replaced with zeros by the framework.</p>
     *
     * @param dsName    Data source name.
     * @param dsType    Data source type. Valid types are "COUNTER",
     *                  "GAUGE", "DERIVE" and "ABSOLUTE" (these string constants are conveniently defined in
     *                  the {@link org.rrd4j.DsType} class).
     * @param heartbeat Data source heartbeat.
     * @param minValue  Minimal acceptable value. Use <code>Double.NaN</code> if unknown.
     * @param maxValue  Maximal acceptable value. Use <code>Double.NaN</code> if unknown.
     * @throws java.lang.IllegalArgumentException Thrown if new datasource definition uses already used data
     *                                  source name.
     */
    public void addDatasource(String dsName, DsType dsType, long heartbeat, double minValue, double maxValue) {
        addDatasource(new DsDef(dsName, dsType, heartbeat, minValue, maxValue));
    }

    /**
     * <p>Adds single datasource to RRD definition from a RRDTool-like
     * datasource definition string. The string must have six elements separated with colons
     * (:) in the following order:</p>
     * <pre>
     * DS:name:type:heartbeat:minValue:maxValue
     * </pre>
     * <p>For example:</p>
     * <pre>
     * DS:input:COUNTER:600:0:U
     * </pre>
     * <p>For more information on datasource definition parameters see <code>rrdcreate</code>
     * man page.</p>
     *
     * @param rrdToolDsDef Datasource definition string with the syntax borrowed from RRDTool.
     * @throws java.lang.IllegalArgumentException Thrown if invalid string is supplied.
     */
    public void addDatasource(String rrdToolDsDef) {
        IllegalArgumentException illArgException = new IllegalArgumentException(
                "Wrong rrdtool-like datasource definition: " + rrdToolDsDef);

        if (rrdToolDsDef == null) throw illArgException;

        StringTokenizer tokenizer = new StringTokenizer(rrdToolDsDef, ":");
        if (tokenizer.countTokens() != 6) {
            throw illArgException;
        }
        String[] tokens = new String[6];
        for (int curTok = 0; tokenizer.hasMoreTokens(); curTok++) {
            tokens[curTok] = tokenizer.nextToken();
        }
        if (!"DS".equalsIgnoreCase(tokens[0])) {
            throw illArgException;
        }
        String dsName = tokens[1];
        DsType dsType = DsType.valueOf(tokens[2]);
        long dsHeartbeat;
        try {
            dsHeartbeat = Long.parseLong(tokens[3]);
        }
        catch (NumberFormatException nfe) {
            throw illArgException;
        }
        double minValue = Double.NaN;
        if (!"U".equalsIgnoreCase(tokens[4])) {
            try {
                minValue = Double.parseDouble(tokens[4]);
            }
            catch (NumberFormatException nfe) {
                throw illArgException;
            }
        }
        double maxValue = Double.NaN;
        if (!"U".equalsIgnoreCase(tokens[5])) {
            try {
                maxValue = Double.parseDouble(tokens[5]);
            }
            catch (NumberFormatException nfe) {
                throw illArgException;
            }
        }
        addDatasource(new DsDef(dsName, dsType, dsHeartbeat, minValue, maxValue));
    }

    /**
     * Adds data source definitions to RRD definition in bulk.
     *
     * @param dsDefs Array of data source definition objects.
     */
    public void addDatasource(DsDef... dsDefs) {
        for (DsDef dsDef : dsDefs) {
            addDatasource(dsDef);
        }
    }

    /**
     * Adds single archive definition represented with object of class <code>ArcDef</code>.
     *
     * @param arcDef Archive definition.
     * @throws java.lang.IllegalArgumentException Thrown if archive with the same consolidation function
     *                                  and the same number of steps is already added.
     */
    public void addArchive(ArcDef arcDef) {
        if (arcDefs.contains(arcDef)) {
            throw new IllegalArgumentException("Archive already defined: " + arcDef.dump());
        }
        arcDefs.add(arcDef);
    }

    /**
     * Adds archive definitions to RRD definition in bulk.
     *
     * @param arcDefs Array of archive definition objects
     * @throws java.lang.IllegalArgumentException Thrown if RRD definition already contains archive with
     *                                  the same consolidation function and the same number of steps.
     */
    public void addArchive(ArcDef... arcDefs) {
        for (ArcDef arcDef : arcDefs) {
            addArchive(arcDef);
        }
    }

    /**
     * Adds single archive definition by specifying its consolidation function, X-files factor,
     * number of steps and rows. For the complete explanation of all archive
     * definition parameters see RRDTool's
     * <a href="../../../../man/rrdcreate.html" target="man">rrdcreate man page</a>.
     *
     * @param consolFun Consolidation function.
     * @param xff       X-files factor. Valid values are between 0 and 1.
     * @param steps     Number of archive steps
     * @param rows      Number of archive rows
     * @throws java.lang.IllegalArgumentException Thrown if archive with the same consolidation function
     *                                  and the same number of steps is already added.
     */
    public void addArchive(ConsolFun consolFun, double xff, int steps, int rows) {
        addArchive(new ArcDef(consolFun, xff, steps, rows));
    }

    /**
     * <p>Adds single archive to RRD definition from a RRDTool-like
     * archive definition string. The string must have five elements separated with colons
     * (:) in the following order:</p>
     * <pre>
     * RRA:consolidationFunction:XFilesFactor:steps:rows
     * </pre>
     * <p>For example:</p>
     * <pre>
     * RRA:AVERAGE:0.5:10:1000
     * </pre>
     * <p>For more information on archive definition parameters see <code>rrdcreate</code>
     * man page.</p>
     *
     * @param rrdToolArcDef Archive definition string with the syntax borrowed from RRDTool.
     * @throws java.lang.IllegalArgumentException Thrown if invalid string is supplied.
     */
    public void addArchive(String rrdToolArcDef) {
        IllegalArgumentException illArgException = new IllegalArgumentException(
                "Wrong rrdtool-like archive definition: " + rrdToolArcDef);
        StringTokenizer tokenizer = new StringTokenizer(rrdToolArcDef, ":");
        if (tokenizer.countTokens() != 5) {
            throw illArgException;
        }
        String[] tokens = new String[5];
        for (int curTok = 0; tokenizer.hasMoreTokens(); curTok++) {
            tokens[curTok] = tokenizer.nextToken();
        }
        if (!"RRA".equalsIgnoreCase(tokens[0])) {
            throw illArgException;
        }
        ConsolFun consolFun = ConsolFun.valueOf(tokens[1]);
        double xff;
        try {
            xff = Double.parseDouble(tokens[2]);
        }
        catch (NumberFormatException nfe) {
            throw illArgException;
        }
        int steps;
        try {
            steps = Integer.parseInt(tokens[3]);
        }
        catch (NumberFormatException nfe) {
            throw illArgException;
        }
        int rows;
        try {
            rows = Integer.parseInt(tokens[4]);
        }
        catch (NumberFormatException nfe) {
            throw illArgException;
        }
        addArchive(new ArcDef(consolFun, xff, steps, rows));
    }

    /**
     * Returns all data source definition objects specified so far.
     *
     * @return Array of data source definition objects
     */
    public DsDef[] getDsDefs() {
        return dsDefs.toArray(new DsDef[dsDefs.size()]);
    }

    /**
     * Returns all archive definition objects specified so far.
     *
     * @return Array of archive definition objects.
     */
    public ArcDef[] getArcDefs() {
        return arcDefs.toArray(new ArcDef[0]);
    }

    /**
     * Returns number of defined datasources.
     *
     * @return Number of defined datasources.
     */
    public int getDsCount() {
        return dsDefs.size();
    }

    /**
     * Returns number of defined archives.
     *
     * @return Number of defined archives.
     */
    public int getArcCount() {
        return arcDefs.size();
    }

    /**
     * Returns string that represents all specified RRD creation parameters. Returned string
     * has the syntax of RRDTool's <code>create</code> command.
     *
     * @return Dumped content of <code>RrdDb</code> object.
     */
    public String dump() {
        StringBuilder sb = new StringBuilder("create \"");
        sb.append(uri)
        .append("\"")
        .append(" --version ").append(getVersion())
        .append(" --start ").append(getStartTime())
        .append(" --step ").append(getStep()).append(" ");
        for (DsDef dsDef : dsDefs) {
            sb.append(dsDef.dump()).append(" ");
        }
        for (ArcDef arcDef : arcDefs) {
            sb.append(arcDef.dump()).append(" ");
        }
        return sb.toString().trim();
    }

    String getRrdToolCommand() {
        return dump();
    }

    void removeDatasource(String dsName) {
        for (int i = 0; i < dsDefs.size(); i++) {
            DsDef dsDef = dsDefs.get(i);
            if (dsDef.getDsName().equals(dsName)) {
                dsDefs.remove(i);
                return;
            }
        }
        throw new IllegalArgumentException("Could not find datasource named '" + dsName + "'");
    }

    void saveSingleDatasource(String dsName) {
        Iterator<DsDef> it = dsDefs.iterator();
        while (it.hasNext()) {
            DsDef dsDef = it.next();
            if (!dsDef.getDsName().equals(dsName)) {
                it.remove();
            }
        }
    }

    void removeArchive(ConsolFun consolFun, int steps) {
        ArcDef arcDef = findArchive(consolFun, steps);
        if (!arcDefs.remove(arcDef)) {
            throw new IllegalArgumentException("Could not remove archive " + consolFun + "/" + steps);
        }
    }

    ArcDef findArchive(ConsolFun consolFun, int steps) {
        for (ArcDef arcDef : arcDefs) {
            if (arcDef.getConsolFun() == consolFun && arcDef.getSteps() == steps) {
                return arcDef;
            }
        }
        throw new IllegalArgumentException("Could not find archive " + consolFun + "/" + steps);
    }

    /**
     * <p>Exports RrdDef object to output stream in XML format. Generated XML code can be parsed
     * with {@link org.rrd4j.core.RrdDefTemplate} class.</p>
     * <p>It use a format compatible with previous RRD4J's version, using
     * a path, instead of an URI.</p>
     *
     * @param out Output stream
     */
    public void exportXmlTemplate(OutputStream out) {
        exportXmlTemplate(out, true);
    }

    /**
     * Exports RrdDef object to output stream in XML format. Generated XML code can be parsed
     * with {@link org.rrd4j.core.RrdDefTemplate} class.
     * <p>If <code>compatible</code> is set to true, it returns an XML compatible with previous RRD4J's versions, using
     * a path, instead of an URI.</p>
     *
     * @param out Output stream
     * @param compatible Compatible with previous versions.
     */
    public void exportXmlTemplate(OutputStream out, boolean compatible) {
        XmlWriter xml = new XmlWriter(out);
        xml.startTag("rrd_def");
        if (compatible) {
            xml.writeTag("path", getPath());
        } else {
            xml.writeTag("uri", getUri());
        }
        xml.writeTag("step", getStep());
        xml.writeTag("start", getStartTime());
        // datasources
        DsDef[] dsDefs = getDsDefs();
        for (DsDef dsDef : dsDefs) {
            xml.startTag("datasource");
            xml.writeTag("name", dsDef.getDsName());
            xml.writeTag("type", dsDef.getDsType());
            xml.writeTag("heartbeat", dsDef.getHeartbeat());
            xml.writeTag("min", dsDef.getMinValue(), "U");
            xml.writeTag("max", dsDef.getMaxValue(), "U");
            xml.closeTag(); // datasource
        }
        ArcDef[] arcDefs = getArcDefs();
        for (ArcDef arcDef : arcDefs) {
            xml.startTag("archive");
            xml.writeTag("cf", arcDef.getConsolFun());
            xml.writeTag("xff", arcDef.getXff());
            xml.writeTag("steps", arcDef.getSteps());
            xml.writeTag("rows", arcDef.getRows());
            xml.closeTag(); // archive
        }
        xml.closeTag(); // rrd_def
        xml.flush();
    }

    /**
     * <p>Exports RrdDef object to string in XML format. Generated XML string can be parsed
     * with {@link org.rrd4j.core.RrdDefTemplate} class.</p>
     * <p>If <code>compatible</code> is set to true, it returns an XML compatible with previous RRD4J's versions, using
     * a path, instead of an URI.</p>
     * 
     *
     * @param compatible Compatible with previous versions.
     * @return XML formatted string representing this RrdDef object
     */
    public String exportXmlTemplate(boolean compatible) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportXmlTemplate(out, compatible);
        return out.toString();
    }

    /**
     * <p>Exports RrdDef object to string in XML format. Generated XML string can be parsed
     * with {@link org.rrd4j.core.RrdDefTemplate} class.</p>
     * <p>It use a format compatible with previous RRD4J's version, using
     * a path, instead of an URI.</p>
     *
     * @return XML formatted string representing this RrdDef object
     */
    public String exportXmlTemplate() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportXmlTemplate(out);
        return out.toString();
    }

    /**
     * <p>Exports RrdDef object to a file in XML format. Generated XML code can be parsed
     * with {@link org.rrd4j.core.RrdDefTemplate} class.</p>
     * <p>It use a format compatible with previous RRD4J's version, using
     * a path, instead of an URI.</p>
     *
     * @param filePath Path to the file
     * @throws java.io.IOException if any.
     */
    public void exportXmlTemplate(String filePath) throws IOException {
        exportXmlTemplate(filePath, true);
    }

    /**
     * <p>Exports RrdDef object to a file in XML format. Generated XML code can be parsed
     * with {@link org.rrd4j.core.RrdDefTemplate} class.</p>
     * <p>If <code>compatible</code> is set to true, it returns an XML compatible with previous RRD4J versions, using
     * a path, instead of an URI.</p>
     *
     * @param filePath Path to the file
     * @param compatible Compatible with previous versions.
     * @throws java.io.IOException if any.
     */
    public void exportXmlTemplate(String filePath, boolean compatible) throws IOException {
        FileOutputStream out = new FileOutputStream(filePath, false);
        exportXmlTemplate(out, compatible);
        out.close();
    }

    /**
     * Returns the number of storage bytes required to create RRD from this
     * RrdDef object.
     *
     * @return Estimated byte count of the underlying RRD storage.
     */
    public long getEstimatedSize() {
        int dsCount = dsDefs.size();
        int arcCount = arcDefs.size();
        int rowsCount = 0;
        for (ArcDef arcDef : arcDefs) {
            rowsCount += arcDef.getRows();
        }
        String[] dsNames = new String[dsCount];
        for (int i = 0; i < dsNames.length ; i++) {
            dsNames[i] = dsDefs.get(i).getDsName();
        }
        return calculateSize(dsCount, arcCount, rowsCount, dsNames);
    }

    static long calculateSize(int dsCount, int arcCount, int rowsCount, String[] dsNames) {
        int postStorePayload = 0;
        for(String n: dsNames) {
            if (n.length() > RrdPrimitive.STRING_LENGTH) {
                postStorePayload += n.length() * 2 + Short.SIZE / 8;
            }
        }
        return (24L + 48L * dsCount + 16L * arcCount +
                20L * dsCount * arcCount + 8L * dsCount * rowsCount) +
                (1L + 2L * dsCount + arcCount) * 2L * RrdPrimitive.STRING_LENGTH +
                postStorePayload;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Compares the current RrdDef with another. RrdDefs are considered equal if:</p>
     * <ul>
     * <li>RRD steps match
     * <li>all datasources have exactly the same definition in both RrdDef objects (datasource names,
     * types, heartbeat, min and max values must match)
     * <li>all archives have exactly the same definition in both RrdDef objects (archive consolidation
     * functions, X-file factors, step and row counts must match)
     * </ul>
     */
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof RrdDef)) {
            return false;
        }
        RrdDef rrdDef2 = (RrdDef) obj;
        // check primary RRD step
        if (step != rrdDef2.step) {
            return false;
        }
        // check datasources
        DsDef[] dsDefs = getDsDefs(), dsDefs2 = rrdDef2.getDsDefs();
        if (dsDefs.length != dsDefs2.length) {
            return false;
        }
        for (DsDef dsDef : dsDefs) {
            boolean matched = false;
            for (DsDef aDsDefs2 : dsDefs2) {
                if (dsDef.exactlyEqual(aDsDefs2)) {
                    matched = true;
                    break;
                }
            }
            // this datasource could not be matched
            if (!matched) {
                return false;
            }
        }
        // check archives
        ArcDef[] arcDefs = getArcDefs(), arcDefs2 = rrdDef2.getArcDefs();
        if (arcDefs.length != arcDefs2.length) {
            return false;
        }
        for (ArcDef arcDef : arcDefs) {
            boolean matched = false;
            for (ArcDef anArcDefs2 : arcDefs2) {
                if (arcDef.exactlyEqual(anArcDefs2)) {
                    matched = true;
                    break;
                }
            }
            // this archive could not be matched
            if (!matched) {
                return false;
            }
        }
        // everything matches
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((arcDefs == null) ? 0 : arcDefs.hashCode());
        result = prime * result + ((dsDefs == null) ? 0 : dsDefs.hashCode());
        result = prime * result + (int) (step ^ (step >>> 32));
        return result;
    }

    /**
     * <p>hasDatasources.</p>
     *
     * @return a boolean.
     */
    public boolean hasDatasources() {
        return !dsDefs.isEmpty();
    }

    /**
     * <p>hasArchives.</p>
     *
     * @return a boolean.
     */
    public boolean hasArchives() {
        return !arcDefs.isEmpty();
    }

    /**
     * Removes all datasource definitions.
     */
    public void removeDatasources() {
        dsDefs.clear();
    }

    /**
     * Removes all RRA archive definitions.
     */
    public void removeArchives() {
        arcDefs.clear();
    }

}
