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

import org.jrobin.data.Aggregates;
import org.jrobin.data.DataProcessor;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Class used to represent data fetched from the RRD.
 * Object of this class is created when the method
 * {@link FetchRequest#fetchData() fetchData()} is
 * called on a {@link FetchRequest FetchRequest} object.
 * <p>
 * Data returned from the RRD is, simply, just one big table filled with
 * timestamps and corresponding datasource values.
 * Use {@link #getRowCount() getRowCount()} method to count the number
 * of returned timestamps (table rows).
 * <p>
 * The first table column is filled with timestamps. Time intervals
 * between consecutive timestamps are guaranteed to be equal. Use
 * {@link #getTimestamps() getTimestamps()} method to get an array of
 * timestamps returned.
 * <p>
 * Remaining columns are filled with datasource values for the whole timestamp range,
 * on a column-per-datasource basis. Use {@link #getColumnCount() getColumnCount()} to find
 * the number of datasources and {@link #getValues(int) getValues(i)} method to obtain
 * all values for the i-th datasource. Returned datasource values correspond to
 * the values returned with {@link #getTimestamps() getTimestamps()} method.
 */
public class FetchData implements ConsolFuns {
	// anything fuuny will do
	private static final String RPN_SOURCE_NAME = "WHERE THE SPEECHLES UNITE IN A SILENT ACCORD";

	private FetchRequest request;
	private String[] dsNames;
	private long[] timestamps;
	private double[][] values;

	private Archive matchingArchive;
	private long arcStep;
	private long arcEndTime;

	FetchData(Archive matchingArchive, FetchRequest request) throws IOException {
		this.matchingArchive = matchingArchive;
		this.arcStep = matchingArchive.getArcStep();
		this.arcEndTime = matchingArchive.getEndTime();
		this.dsNames = request.getFilter();
		if (this.dsNames == null) {
			this.dsNames = matchingArchive.getParentDb().getDsNames();
		}
		this.request = request;
	}

	void setTimestamps(long[] timestamps) {
		this.timestamps = timestamps;
	}

	void setValues(double[][] values) {
		this.values = values;
	}

	/**
	 * Returns the number of rows fetched from the corresponding RRD.
	 * Each row represents datasource values for the specific timestamp.
	 *
	 * @return Number of rows.
	 */
	public int getRowCount() {
		return timestamps.length;
	}

	/**
	 * Returns the number of columns fetched from the corresponding RRD.
	 * This number is always equal to the number of datasources defined
	 * in the RRD. Each column represents values of a single datasource.
	 *
	 * @return Number of columns (datasources).
	 */
	public int getColumnCount() {
		return dsNames.length;
	}

	/**
	 * Returns an array of timestamps covering the whole range specified in the
	 * {@link FetchRequest FetchReguest} object.
	 *
	 * @return Array of equidistant timestamps.
	 */
	public long[] getTimestamps() {
		return timestamps;
	}

	/**
	 * Returns the step with which this data was fetched.
	 *
	 * @return Step as long.
	 */
	public long getStep() {
		return timestamps[1] - timestamps[0];
	}

	/**
	 * Returns all archived values for a single datasource.
	 * Returned values correspond to timestamps
	 * returned with {@link #getTimestamps() getTimestamps()} method.
	 *
	 * @param dsIndex Datasource index.
	 * @return Array of single datasource values.
	 */
	public double[] getValues(int dsIndex) {
		return values[dsIndex];
	}

	/**
	 * Returns all archived values for all datasources.
	 * Returned values correspond to timestamps
	 * returned with {@link #getTimestamps() getTimestamps()} method.
	 *
	 * @return Two-dimensional aray of all datasource values.
	 */
	public double[][] getValues() {
		return values;
	}

	/**
	 * Returns all archived values for a single datasource.
	 * Returned values correspond to timestamps
	 * returned with {@link #getTimestamps() getTimestamps()} method.
	 *
	 * @param dsName Datasource name.
	 * @return Array of single datasource values.
	 * @throws RrdException Thrown if no matching datasource name is found.
	 */
	public double[] getValues(String dsName) throws RrdException {
		for (int dsIndex = 0; dsIndex < getColumnCount(); dsIndex++) {
			if (dsName.equals(dsNames[dsIndex])) {
				return getValues(dsIndex);
			}
		}
		throw new RrdException("Datasource [" + dsName + "] not found");
	}

	/**
	 * Returns a set of values created by applying RPN expression to the fetched data.
	 * For example, if you have two datasources named <code>x</code> and <code>y</code>
	 * in this FetchData and you want to calculate values for <code>(x+y)/2</code> use something like:
	 * <p>
	 * <code>getRpnValues("x,y,+,2,/");</code>
	 *
	 * @param rpnExpression RRDTool-like RPN expression
	 * @return Calculated values
	 * @throws RrdException Thrown if invalid RPN expression is supplied
	 */
	public double[] getRpnValues(String rpnExpression) throws RrdException {
		DataProcessor dataProcessor = createDataProcessor(rpnExpression);
		return dataProcessor.getValues(RPN_SOURCE_NAME);
	}

	/**
	 * Returns {@link FetchRequest FetchRequest} object used to create this FetchData object.
	 *
	 * @return Fetch request object.
	 */
	public FetchRequest getRequest() {
		return request;
	}

	/**
	 * Returns the first timestamp in this FetchData object.
	 *
	 * @return The smallest timestamp.
	 */
	public long getFirstTimestamp() {
		return timestamps[0];
	}

	/**
	 * Returns the last timestamp in this FecthData object.
	 *
	 * @return The biggest timestamp.
	 */
	public long getLastTimestamp() {
		return timestamps[timestamps.length - 1];
	}

	/**
	 * Returns Archive object which is determined to be the best match for the
	 * timestamps specified in the fetch request. All datasource values are obtained
	 * from round robin archives belonging to this archive.
	 *
	 * @return Matching archive.
	 */
	public Archive getMatchingArchive() {
		return matchingArchive;
	}

	/**
	 * Returns array of datasource names found in the corresponding RRD. If the request
	 * was filtered (data was fetched only for selected datasources), only datasources selected
	 * for fetching are returned.
	 *
	 * @return Array of datasource names.
	 */
	public String[] getDsNames() {
		return dsNames;
	}

	/**
	 * Retrieve the table index number of a datasource by name.  Names are case sensitive.
	 *
	 * @param dsName Name of the datasource for which to find the index.
	 * @return Index number of the datasources in the value table.
	 */
	public int getDsIndex(String dsName) {
		// Let's assume the table of dsNames is always small, so it is not necessary to use a hashmap for lookups
		for (int i = 0; i < dsNames.length; i++) {
			if (dsNames[i].equals(dsName)) {
				return i;
			}
		}
		return -1;		// Datasource not found !
	}

	/**
	 * Dumps the content of the whole FetchData object. Useful for debugging.
	 *
	 * @return String containing the contents of this object, for debugging.
	 */
	public String dump() {
		StringBuffer buffer = new StringBuffer("");
		for (int row = 0; row < getRowCount(); row++) {
			buffer.append(timestamps[row]);
			buffer.append(":  ");
			for (int dsIndex = 0; dsIndex < getColumnCount(); dsIndex++) {
				buffer.append(Util.formatDouble(values[dsIndex][row], true));
				buffer.append("  ");
			}
			buffer.append("\n");
		}
		return buffer.toString();
	}

	/**
	 * Returns string representing fetched data in a RRDTool-like form.
	 *
	 * @return Fetched data as a string in a rrdfetch-like output form.
	 */
	public String toString() {
		// print header row
		StringBuffer buff = new StringBuffer();
		buff.append(padWithBlanks("", 10));
		buff.append(" ");
		for (String dsName : dsNames) {
			buff.append(padWithBlanks(dsName, 18));
		}
		buff.append("\n \n");
		for (int i = 0; i < timestamps.length; i++) {
			buff.append(padWithBlanks("" + timestamps[i], 10));
			buff.append(":");
			for (int j = 0; j < dsNames.length; j++) {
				double value = values[j][i];
				String valueStr = Double.isNaN(value) ? "nan" : Util.formatDouble(value);
				buff.append(padWithBlanks(valueStr, 18));
			}
			buff.append("\n");
		}
		return buff.toString();
	}

	private static String padWithBlanks(String input, int width) {
		StringBuffer buff = new StringBuffer("");
		int diff = width - input.length();
		while (diff-- > 0) {
			buff.append(' ');
		}
		buff.append(input);
		return buff.toString();
	}

	/**
	 * Returns single aggregated value from the fetched data for a single datasource.
	 *
	 * @param dsName	Datasource name
	 * @param consolFun Consolidation function to be applied to fetched datasource values.
	 *                  Valid consolidation functions are "MIN", "MAX", "LAST", "FIRST", "AVERAGE" and "TOTAL"
	 *                  (these string constants are conveniently defined in the {@link ConsolFuns} class)
	 * @return MIN, MAX, LAST, FIRST, AVERAGE or TOTAL value calculated from the fetched data
	 *         for the given datasource name
	 * @throws RrdException Thrown if the given datasource name cannot be found in fetched data.
	 */
	public double getAggregate(String dsName, String consolFun) throws RrdException {
		DataProcessor dp = createDataProcessor(null);
		return dp.getAggregate(dsName, consolFun);
	}

	/**
	 * Returns aggregated value from the fetched data for a single datasource.
	 * Before applying aggregation functions, specified RPN expression is applied to fetched data.
	 * For example, if you have a gauge datasource named 'foots' but you want to find the maximum
	 * fetched value in meters use something like:
	 * <p>
	 * <code>getAggregate("foots", "MAX", "foots,0.3048,*");</code>
	 *
	 * @param dsName		Datasource name
	 * @param consolFun	 Consolidation function (MIN, MAX, LAST, FIRST, AVERAGE or TOTAL)
	 * @param rpnExpression RRDTool-like RPN expression
	 * @return Aggregated value
	 * @throws RrdException Thrown if the given datasource name cannot be found in fetched data, or if
	 *                      invalid RPN expression is supplied
	 * @throws IOException  Thrown in case of I/O error (unlikely to happen)
	 * @deprecated This method is preserved just for backward compatibility.
	 */
	public double getAggregate(String dsName, String consolFun, String rpnExpression)
			throws RrdException, IOException {
		// for backward compatibility
		rpnExpression = rpnExpression.replaceAll("value", dsName);
		return getRpnAggregate(rpnExpression, consolFun);
	}

	/**
	 * Returns aggregated value for a set of values calculated by applying an RPN expression to the
	 * fetched data. For example, if you have two datasources named <code>x</code> and <code>y</code>
	 * in this FetchData and you want to calculate MAX value of <code>(x+y)/2</code> use something like:
	 * <p>
	 * <code>getRpnAggregate("x,y,+,2,/", "MAX");</code>
	 *
	 * @param rpnExpression RRDTool-like RPN expression
	 * @param consolFun	 Consolidation function (MIN, MAX, LAST, FIRST, AVERAGE or TOTAL)
	 * @return Aggregated value
	 * @throws RrdException Thrown if invalid RPN expression is supplied
	 */
	public double getRpnAggregate(String rpnExpression, String consolFun) throws RrdException {
		DataProcessor dataProcessor = createDataProcessor(rpnExpression);
		return dataProcessor.getAggregate(RPN_SOURCE_NAME, consolFun);
	}

	/**
	 * Returns all aggregated values (MIN, MAX, LAST, FIRST, AVERAGE or TOTAL) calculated from the fetched data
	 * for a single datasource.
	 *
	 * @param dsName Datasource name.
	 * @return Simple object containing all aggregated values.
	 * @throws RrdException Thrown if the given datasource name cannot be found in the fetched data.
	 */
	public Aggregates getAggregates(String dsName) throws RrdException {
		DataProcessor dataProcessor = createDataProcessor(null);
		return dataProcessor.getAggregates(dsName);
	}

	/**
	 * Returns all aggregated values for a set of values calculated by applying an RPN expression to the
	 * fetched data. For example, if you have two datasources named <code>x</code> and <code>y</code>
	 * in this FetchData and you want to calculate MIN, MAX, LAST, FIRST, AVERAGE and TOTAL value
	 * of <code>(x+y)/2</code> use something like:
	 * <p>
	 * <code>getRpnAggregates("x,y,+,2,/");</code>
	 *
	 * @param rpnExpression RRDTool-like RPN expression
	 * @return Object containing all aggregated values
	 * @throws RrdException Thrown if invalid RPN expression is supplied
	 * @throws IOException Thrown in case of I/O error
	 */
	public Aggregates getRpnAggregates(String rpnExpression) throws RrdException, IOException {
		DataProcessor dataProcessor = createDataProcessor(rpnExpression);
		return dataProcessor.getAggregates(RPN_SOURCE_NAME);
	}

	/**
	 * Used by ISPs which charge for bandwidth utilization on a "95th percentile" basis.
	 * <p>
	 * The 95th percentile is the highest source value left when the top 5% of a numerically sorted set
	 * of source data is discarded. It is used as a measure of the peak value used when one discounts
	 * a fair amount for transitory spikes. This makes it markedly different from the average.
	 * <p>
	 * Read more about this topic at:
	 * <a href="http://www.red.net/support/resourcecentre/leasedline/percentile.php">Rednet</a> or<br>
	 * <a href="http://www.bytemark.co.uk/support/tech/95thpercentile.html">Bytemark</a>.
	 *
	 * @param dsName Datasource name
	 * @return 95th percentile of fetched source values
	 * @throws RrdException Thrown if invalid source name is supplied
	 */
	public double get95Percentile(String dsName) throws RrdException {
		DataProcessor dataProcessor = createDataProcessor(null);
		return dataProcessor.get95Percentile(dsName);
	}

	/**
	 * Same as {@link #get95Percentile(String)}, but for a set of values calculated with the given
	 * RPN expression.
	 *
	 * @param rpnExpression RRDTool-like RPN expression
	 * @return 95-percentile
	 * @throws RrdException Thrown if invalid RPN expression is supplied
	 */
	public double getRpn95Percentile(String rpnExpression) throws RrdException {
		DataProcessor dataProcessor = createDataProcessor(rpnExpression);
		return dataProcessor.get95Percentile(RPN_SOURCE_NAME);
	}

	/**
	 * Dumps fetch data to output stream in XML format.
	 *
	 * @param outputStream Output stream to dump fetch data to
	 * @throws IOException Thrown in case of I/O error
	 */
	public void exportXml(OutputStream outputStream) throws IOException {
		XmlWriter writer = new XmlWriter(outputStream);
		writer.startTag("fetch_data");
		writer.startTag("request");
		writer.writeTag("file", request.getParentDb().getPath());
		writer.writeComment(Util.getDate(request.getFetchStart()));
		writer.writeTag("start", request.getFetchStart());
		writer.writeComment(Util.getDate(request.getFetchEnd()));
		writer.writeTag("end", request.getFetchEnd());
		writer.writeTag("resolution", request.getResolution());
		writer.writeTag("cf", request.getConsolFun());
		writer.closeTag(); // request
		writer.startTag("datasources");
		for (String dsName : dsNames) {
			writer.writeTag("name", dsName);
		}
		writer.closeTag(); // datasources
		writer.startTag("data");
		for (int i = 0; i < timestamps.length; i++) {
			writer.startTag("row");
			writer.writeComment(Util.getDate(timestamps[i]));
			writer.writeTag("timestamp", timestamps[i]);
			writer.startTag("values");
			for (int j = 0; j < dsNames.length; j++) {
				writer.writeTag("v", values[j][i]);
			}
			writer.closeTag(); // values
			writer.closeTag(); // row
		}
		writer.closeTag(); // data
		writer.closeTag(); // fetch_data
		writer.flush();
	}

	/**
	 * Dumps fetch data to file in XML format.
	 *
	 * @param filepath Path to destination file
	 * @throws IOException Thrown in case of I/O error
	 */
	public void exportXml(String filepath) throws IOException {
		OutputStream outputStream = null;
		try {
			outputStream = new FileOutputStream(filepath);
			exportXml(outputStream);
		}
		finally {
			if (outputStream != null) {
				outputStream.close();
			}
		}
	}

	/**
	 * Dumps fetch data in XML format.
	 *
	 * @return String containing XML formatted fetch data
	 * @throws IOException Thrown in case of I/O error
	 */
	public String exportXml() throws IOException {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		exportXml(outputStream);
		return outputStream.toString();
	}

	/**
	 * Returns the step of the corresponding RRA archive
	 *
	 * @return Archive step in seconds
	 */
	public long getArcStep() {
		return arcStep;
	}

	/**
	 * Returns the timestamp of the last populated slot in the corresponding RRA archive
	 *
	 * @return Timestamp in seconds
	 */
	public long getArcEndTime() {
		return arcEndTime;
	}

	private DataProcessor createDataProcessor(String rpnExpression) throws RrdException {
		DataProcessor dataProcessor = new DataProcessor(request.getFetchStart(), request.getFetchEnd());
		for (String dsName : dsNames) {
			dataProcessor.addDatasource(dsName, this);
		}
		if (rpnExpression != null) {
			dataProcessor.addDatasource(RPN_SOURCE_NAME, rpnExpression);
			try {
				dataProcessor.processData();
			}
			catch (IOException ioe) {
				// highly unlikely, since all datasources have already calculated values
				throw new RuntimeException("Impossible error: " + ioe);
			}
		}
		return dataProcessor;
	}
}
