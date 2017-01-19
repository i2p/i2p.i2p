/*******************************************************************************
 * Copyright (c) 2001-2005 Sasa Markovic and Ciaran Treanor.
 * Copyright (c) 2011 The OpenNMS Group, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *******************************************************************************/

package org.jrobin.core;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

/**
 * Class to represent definition of new Round Robin Database (RRD).
 * Object of this class is used to create
 * new RRD from scratch - pass its reference as a <code>RrdDb</code> constructor
 * argument (see documentation for {@link RrdDb RrdDb} class). <code>RrdDef</code>
 * object <b>does not</b> actually create new RRD. It just holds all necessary
 * information which will be used during the actual creation process.
 * <p>
 * RRD definition (RrdDef object) consists of the following elements:
 * <p>
 * <ul>
 * <li> path to RRD that will be created
 * <li> starting timestamp
 * <li> step
 * <li> one or more datasource definitions
 * <li> one or more archive definitions
 * </ul>
 * RrdDef provides API to set all these elements. For the complete explanation of all
 * RRD definition parameters, see RRDTool's
 * <a href="../../../../man/rrdcreate.html" target="man">rrdcreate man page</a>.
 * <p>
 *
 * @author <a href="mailto:saxon@jrobin.org">Sasa Markovic</a>
 */
public class RrdDef {
	/**
	 * default RRD step to be used if not specified in constructor (300 seconds)
	 */
	public static final long DEFAULT_STEP = 300L;
	/**
	 * if not specified in constructor, starting timestamp will be set to the
	 * current timestamp plus DEFAULT_INITIAL_SHIFT seconds (-10)
	 */
	public static final long DEFAULT_INITIAL_SHIFT = -10L;

	private String path;
	private long startTime = Util.getTime() + DEFAULT_INITIAL_SHIFT;
	private long step = DEFAULT_STEP;
	private ArrayList<DsDef> dsDefs = new ArrayList<DsDef>();
	private ArrayList<ArcDef> arcDefs = new ArrayList<ArcDef>();

	/**
	 * <p>Creates new RRD definition object with the given path.
	 * When this object is passed to
	 * <code>RrdDb</code> constructor, new RRD will be created using the
	 * specified path. </p>
	 *
	 * @param path Path to new RRD.
	 * @throws RrdException Thrown if name is invalid (null or empty).
	 */
	public RrdDef(final String path) throws RrdException {
		if (path == null || path.length() == 0) {
			throw new RrdException("No path specified");
		}
		this.path = path;
	}

	/**
	 * <p>Creates new RRD definition object with the given path and step.</p>
	 *
	 * @param path Path to new RRD.
	 * @param step RRD step.
	 * @throws RrdException Thrown if supplied parameters are invalid.
	 */
	public RrdDef(final String path, final long step) throws RrdException {
		this(path);
		if (step <= 0) {
			throw new RrdException("Invalid RRD step specified: " + step);
		}
		this.step = step;
	}

	/**
	 * <p>Creates new RRD definition object with the given path, starting timestamp
	 * and step.</p>
	 *
	 * @param path	  Path to new RRD.
	 * @param startTime RRD starting timestamp.
	 * @param step	  RRD step.
	 * @throws RrdException Thrown if supplied parameters are invalid.
	 */
	public RrdDef(final String path, final long startTime, final long step) throws RrdException {
		this(path, step);
		if (startTime < 0) {
			throw new RrdException("Invalid RRD start time specified: " + startTime);
		}
		this.startTime = startTime;
	}

	/**
	 * Returns path for the new RRD
	 *
	 * @return path to the new RRD which should be created
	 */
	public String getPath() {
		return path;
	}

	/**
	 * Returns starting timestamp for the RRD that should be created.
	 *
	 * @return RRD starting timestamp
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
	 * Sets path to RRD.
	 *
	 * @param path to new RRD.
	 */
	public void setPath(final String path) {
		this.path = path;
	}

	/**
	 * Sets RRD's starting timestamp.
	 *
	 * @param startTime starting timestamp.
	 */
	public void setStartTime(final long startTime) {
		this.startTime = startTime;
	}

	/**
	 * Sets RRD's starting timestamp.
	 *
	 * @param date starting date
	 */
	public void setStartTime(final Date date) {
		this.startTime = Util.getTimestamp(date);
	}

	/**
	 * Sets RRD's starting timestamp.
	 *
	 * @param gc starting date
	 */
	public void setStartTime(final Calendar gc) {
		this.startTime = Util.getTimestamp(gc);
	}

	/**
	 * Sets RRD's time step.
	 *
	 * @param step RRD time step.
	 */
	public void setStep(final long step) {
		this.step = step;
	}

	/**
	 * Adds single datasource definition represented with object of class <code>DsDef</code>.
	 *
	 * @param dsDef Datasource definition.
	 * @throws RrdException Thrown if new datasource definition uses already used data
	 *                      source name.
	 */
	public void addDatasource(final DsDef dsDef) throws RrdException {
		if (dsDefs.contains(dsDef)) {
			throw new RrdException("Datasource already defined: " + dsDef.dump());
		}
		dsDefs.add(dsDef);
	}

	/**
	 * Adds single datasource to RRD definition by specifying its data source name, source type,
	 * heartbeat, minimal and maximal value. For the complete explanation of all data
	 * source definition parameters see RRDTool's
	 * <a href="../../../../man/rrdcreate.html" target="man">rrdcreate man page</a>.
	 * <p>
	 * <b>IMPORTANT NOTE:</b> If datasource name ends with '!', corresponding archives will never
	 * store NaNs as datasource values. In that case, NaN datasource values will be silently
	 * replaced with zeros by the framework.
	 *
	 * @param dsName	Data source name.
	 * @param dsType	Data source type. Valid types are "COUNTER",
	 *                  "GAUGE", "DERIVE" and "ABSOLUTE" (these string constants are conveniently defined in
	 *                  the {@link DsTypes} class).
	 * @param heartbeat Data source heartbeat.
	 * @param minValue  Minimal acceptable value. Use <code>Double.NaN</code> if unknown.
	 * @param maxValue  Maximal acceptable value. Use <code>Double.NaN</code> if unknown.
	 * @throws RrdException Thrown if new datasource definition uses already used data
	 *                      source name.
	 */
	public void addDatasource(final String dsName, final String dsType, final long heartbeat, final double minValue, final double maxValue) throws RrdException {
		addDatasource(new DsDef(dsName, dsType, heartbeat, minValue, maxValue));
	}

	/**
	 * Adds single datasource to RRD definition from a RRDTool-like
	 * datasource definition string. The string must have six elements separated with colons
	 * (:) in the following order:
	 * <p>
	 * <pre>
	 * DS:name:type:heartbeat:minValue:maxValue
	 * </pre>
	 * For example:
	 * <p>
	 * <pre>
	 * DS:input:COUNTER:600:0:U
	 * </pre>
	 * For more information on datasource definition parameters see <code>rrdcreate</code>
	 * man page.
	 *
	 * @param rrdToolDsDef Datasource definition string with the syntax borrowed from RRDTool.
	 * @throws RrdException Thrown if invalid string is supplied.
	 */
	public void addDatasource(final String rrdToolDsDef) throws RrdException {
		final RrdException rrdException = new RrdException("Wrong rrdtool-like datasource definition: " + rrdToolDsDef);
		final StringTokenizer tokenizer = new StringTokenizer(rrdToolDsDef, ":");
		if (tokenizer.countTokens() != 6) {
			throw rrdException;
		}
		final String[] tokens = new String[6];
		for (int curTok = 0; tokenizer.hasMoreTokens(); curTok++) {
			tokens[curTok] = tokenizer.nextToken();
		}
		if (!tokens[0].equalsIgnoreCase("DS")) {
			throw rrdException;
		}
		final String dsName = tokens[1];
		final String dsType = tokens[2];
		long dsHeartbeat;
		try {
			dsHeartbeat = Long.parseLong(tokens[3]);
		}
		catch (final NumberFormatException nfe) {
			throw rrdException;
		}
		double minValue = Double.NaN;
		if (!tokens[4].equalsIgnoreCase("U")) {
			try {
				minValue = Double.parseDouble(tokens[4]);
			}
			catch (final NumberFormatException nfe) {
				throw rrdException;
			}
		}
		double maxValue = Double.NaN;
		if (!tokens[5].equalsIgnoreCase("U")) {
			try {
				maxValue = Double.parseDouble(tokens[5]);
			}
			catch (final NumberFormatException nfe) {
				throw rrdException;
			}
		}
		addDatasource(new DsDef(dsName, dsType, dsHeartbeat, minValue, maxValue));
	}

	/**
	 * Adds data source definitions to RRD definition in bulk.
	 *
	 * @param dsDefs Array of data source definition objects.
	 * @throws RrdException Thrown if duplicate data source name is used.
	 */
	public void addDatasource(final DsDef[] dsDefs) throws RrdException {
		for (final DsDef dsDef : dsDefs) {
			addDatasource(dsDef);
		}
	}

	/**
	 * Adds single archive definition represented with object of class <code>ArcDef</code>.
	 *
	 * @param arcDef Archive definition.
	 * @throws RrdException Thrown if archive with the same consolidation function
	 *                      and the same number of steps is already added.
	 */
	public void addArchive(final ArcDef arcDef) throws RrdException {
		if (arcDefs.contains(arcDef)) {
			throw new RrdException("Archive already defined: " + arcDef.dump());
		}
		arcDefs.add(arcDef);
	}

	/**
	 * Adds archive definitions to RRD definition in bulk.
	 *
	 * @param arcDefs Array of archive definition objects
	 * @throws RrdException Thrown if RRD definition already contains archive with
	 *                      the same consolidation function and the same number of steps.
	 */
	public void addArchive(final ArcDef[] arcDefs) throws RrdException {
		for (final ArcDef arcDef : arcDefs) {
			addArchive(arcDef);
		}
	}

	/**
	 * Adds single archive definition by specifying its consolidation function, X-files factor,
	 * number of steps and rows. For the complete explanation of all archive
	 * definition parameters see RRDTool's
	 * <a href="../../../../man/rrdcreate.html" target="man">rrdcreate man page</a>.
	 * <p>
	 *
	 * @param consolFun Consolidation function. Valid values are "AVERAGE",
	 *                  "MIN", "MAX" and "LAST" (these constants are conveniently defined in the
	 *                  {@link ConsolFuns} class)
	 * @param xff	   X-files factor. Valid values are between 0 and 1.
	 * @param steps	 Number of archive steps
	 * @param rows	  Number of archive rows
	 * @throws RrdException Thrown if archive with the same consolidation function
	 *                      and the same number of steps is already added.
	 */
	public void addArchive(final String consolFun, final double xff, final int steps, final int rows) throws RrdException {
		addArchive(new ArcDef(consolFun, xff, steps, rows));
	}

	/**
	 * Adds single archive to RRD definition from a RRDTool-like
	 * archive definition string. The string must have five elements separated with colons
	 * (:) in the following order:
	 * <p>
	 * <pre>
	 * RRA:consolidationFunction:XFilesFactor:steps:rows
	 * </pre>
	 * For example:
	 * <p>
	 * <pre>
	 * RRA:AVERAGE:0.5:10:1000
	 * </pre>
	 * For more information on archive definition parameters see <code>rrdcreate</code>
	 * man page.
	 *
	 * @param rrdToolArcDef Archive definition string with the syntax borrowed from RRDTool.
	 * @throws RrdException Thrown if invalid string is supplied.
	 */
	public void addArchive(final String rrdToolArcDef) throws RrdException {
	    final RrdException rrdException = new RrdException("Wrong rrdtool-like archive definition: " + rrdToolArcDef);
		final StringTokenizer tokenizer = new StringTokenizer(rrdToolArcDef, ":");
		if (tokenizer.countTokens() != 5) {
			throw rrdException;
		}
		final String[] tokens = new String[5];
		for (int curTok = 0; tokenizer.hasMoreTokens(); curTok++) {
			tokens[curTok] = tokenizer.nextToken();
		}
		if (!tokens[0].equalsIgnoreCase("RRA")) {
			throw rrdException;
		}
		final String consolFun = tokens[1];
		double xff;
		try {
			xff = Double.parseDouble(tokens[2]);
		}
		catch (final NumberFormatException nfe) {
			throw rrdException;
		}
		int steps;
		try {
			steps = Integer.parseInt(tokens[3]);
		}
		catch (final NumberFormatException nfe) {
			throw rrdException;
		}
		int rows;
		try {
			rows = Integer.parseInt(tokens[4]);
		}
		catch (final NumberFormatException nfe) {
			throw rrdException;
		}
		addArchive(new ArcDef(consolFun, xff, steps, rows));
	}

	void validate() throws RrdException {
		if (dsDefs.size() == 0) {
			throw new RrdException("No RRD datasource specified. At least one is needed.");
		}
		if (arcDefs.size() == 0) {
			throw new RrdException("No RRD archive specified. At least one is needed.");
		}
	}

	/**
	 * Returns all data source definition objects specified so far.
	 *
	 * @return Array of data source definition objects
	 */
	public DsDef[] getDsDefs() {
		return dsDefs.toArray(new DsDef[0]);
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
	    final StringBuffer buffer = new StringBuffer("create \"");
		buffer.append(path).append("\"");
		buffer.append(" --start ").append(getStartTime());
		buffer.append(" --step ").append(getStep()).append(" ");
		for (final DsDef dsDef : dsDefs) {
			buffer.append(dsDef.dump()).append(" ");
		}
		for (final ArcDef arcDef : arcDefs) {
			buffer.append(arcDef.dump()).append(" ");
		}
		return buffer.toString().trim();
	}

	String getRrdToolCommand() {
		return dump();
	}

	void removeDatasource(final String dsName) throws RrdException {
		for (int i = 0; i < dsDefs.size(); i++) {
		    final DsDef dsDef = dsDefs.get(i);
			if (dsDef.getDsName().equals(dsName)) {
				dsDefs.remove(i);
				return;
			}
		}
		throw new RrdException("Could not find datasource named '" + dsName + "'");
	}

	void saveSingleDatasource(final String dsName) {
	    final Iterator<DsDef> it = dsDefs.iterator();
		while (it.hasNext()) {
			final DsDef dsDef = it.next();
			if (!dsDef.getDsName().equals(dsName)) {
				it.remove();
			}
		}
	}

	void removeArchive(final String consolFun, final int steps) throws RrdException {
	    final ArcDef arcDef = findArchive(consolFun, steps);
		if (!arcDefs.remove(arcDef)) {
			throw new RrdException("Could not remove archive " + consolFun + "/" + steps);
		}
	}

	ArcDef findArchive(final String consolFun, final int steps) throws RrdException {
	    for (final ArcDef arcDef : arcDefs) {
			if (arcDef.getConsolFun().equals(consolFun) && arcDef.getSteps() == steps) {
				return arcDef;
			}
		}
		throw new RrdException("Could not find archive " + consolFun + "/" + steps);
	}

	/**
	 * Exports RrdDef object to output stream in XML format. Generated XML code can be parsed
	 * with {@link RrdDefTemplate} class.
	 *
	 * @param out Output stream
	 */
	public void exportXmlTemplate(final OutputStream out) {
	    final XmlWriter xml = new XmlWriter(out);
		xml.startTag("rrd_def");
		xml.writeTag("path", getPath());
		xml.writeTag("step", getStep());
		xml.writeTag("start", getStartTime());
		for (final DsDef dsDef : getDsDefs()) {
			xml.startTag("datasource");
			xml.writeTag("name", dsDef.getDsName());
			xml.writeTag("type", dsDef.getDsType());
			xml.writeTag("heartbeat", dsDef.getHeartbeat());
			xml.writeTag("min", dsDef.getMinValue(), "U");
			xml.writeTag("max", dsDef.getMaxValue(), "U");
			xml.closeTag(); // datasource
		}
		for (ArcDef arcDef : getArcDefs()) {
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
	 * Exports RrdDef object to string in XML format. Generated XML string can be parsed
	 * with {@link RrdDefTemplate} class.
	 *
	 * @return XML formatted string representing this RrdDef object
	 */
	public String exportXmlTemplate() {
	    final ByteArrayOutputStream out = new ByteArrayOutputStream();
		exportXmlTemplate(out);
		return out.toString();
	}

	/**
	 * Exports RrdDef object to a file in XML format. Generated XML code can be parsed
	 * with {@link RrdDefTemplate} class.
	 *
	 * @param filePath path to the file
	 * @throws IOException if an I/O error occurs.
	 */
	public void exportXmlTemplate(final String filePath) throws IOException {
	    final FileOutputStream out = new FileOutputStream(filePath, false);
		exportXmlTemplate(out);
		out.close();
	}

	/**
	 * Returns the number of storage bytes required to create RRD from this
	 * RrdDef object.
	 *
	 * @return Estimated byte count of the underlying RRD storage.
	 */
	public long getEstimatedSize() {
		final int dsCount = dsDefs.size();
		final int arcCount = arcDefs.size();
		int rowsCount = 0;
		for (final ArcDef arcDef : arcDefs) {
			rowsCount += arcDef.getRows();
		}
		return calculateSize(dsCount, arcCount, rowsCount);
	}

	static long calculateSize(final int dsCount, final int arcCount, final int rowsCount) {
		return (24L + 48L * dsCount + 16L * arcCount +
				20L * dsCount * arcCount + 8L * dsCount * rowsCount) +
				(1L + 2L * dsCount + arcCount) * 2L * RrdPrimitive.STRING_LENGTH;
	}

	/**
	 * Compares the current RrdDef with another. RrdDefs are considered equal if:<p>
	 * <ul>
	 * <li>RRD steps match
	 * <li>all datasources have exactly the same definition in both RrdDef objects (datasource names,
	 * types, heartbeat, min and max values must match)
	 * <li>all archives have exactly the same definition in both RrdDef objects (archive consolidation
	 * functions, X-file factors, step and row counts must match)
	 * </ul>
	 *
	 * @param obj The second RrdDef object
	 * @return true if RrdDefs match exactly, false otherwise
	 */
	public boolean equals(final Object obj) {
		if (obj == null || !(obj instanceof RrdDef)) {
			return false;
		}
		final RrdDef rrdDef2 = (RrdDef) obj;
		// check primary RRD step
		if (step != rrdDef2.step) {
			return false;
		}
		// check datasources
		final DsDef[] dsDefs = getDsDefs();
		final DsDef[] dsDefs2 = rrdDef2.getDsDefs();
		if (dsDefs.length != dsDefs2.length) {
			return false;
		}
		for (final DsDef dsDef : dsDefs) {
			boolean matched = false;
			for (final DsDef dsDef2 : dsDefs2) {
				if (dsDef.exactlyEqual(dsDef2)) {
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
		final ArcDef[] arcDefs = getArcDefs();
		final ArcDef[] arcDefs2 = rrdDef2.getArcDefs();
		if (arcDefs.length != arcDefs2.length) {
			return false;
		}
		for (final ArcDef arcDef : arcDefs) {
			boolean matched = false;
			for (final ArcDef arcDef2 : arcDefs2) {
				if (arcDef.exactlyEqual(arcDef2)) {
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

	public int hashCode() {
		int hashCode = (int)step;
		for (final DsDef dsDef : dsDefs) {
			hashCode *= dsDef.hashCode();
		}
		for (final ArcDef arcDef : arcDefs) {
			hashCode *= arcDef.hashCode();
		}
		return hashCode;
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

    public String toString() {
        return getClass().getSimpleName() + "@" + Integer.toHexString(hashCode()) + "[arcDefs=[" + join(getArcDefs()) + "],dsDefs=[" + join(getDsDefs()) + "]]";
    }

    private String join(final Object[] objs) {
        final StringBuffer sb = new StringBuffer();
        for (int i = 0; i < objs.length; i++) {
            sb.append(objs[i]);
            if (i != (objs.length - 1)) {
                sb.append(",");
            }
        }
        return sb.toString();
    }
}
