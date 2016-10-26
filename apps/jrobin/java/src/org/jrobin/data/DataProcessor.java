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

package org.jrobin.data;

import org.jrobin.core.*;

import java.io.IOException;
import java.util.*;

/**
 * Class which should be used for all calculations based on the data fetched from RRD files. This class
 * supports ordinary DEF datasources (defined in RRD files), CDEF datasources (RPN expressions evaluation),
 * SDEF (static datasources - extension of JRobin) and PDEF (plottables, see
 * {@link Plottable Plottable} for more information.
 * <p>
 * Typical class usage:
 * <p>
 * <pre>
 * final long t1 = ...
 * final long t2 = ...
 * DataProcessor dp = new DataProcessor(t1, t2);
 * // DEF datasource
 * dp.addDatasource("x", "demo.rrd", "some_source", "AVERAGE");
 * // DEF datasource
 * dp.addDatasource("y", "demo.rrd", "some_other_source", "AVERAGE");
 * // CDEF datasource, z = (x + y) / 2
 * dp.addDatasource("z", "x,y,+,2,/");
 * // ACTION!
 * dp.processData();
 * // Dump calculated values
 * System.out.println(dp.dump());
 * </pre>
 */
public class DataProcessor implements ConsolFuns {
	/**
	 * Constant representing the default number of pixels on a JRobin graph (will be used if
	 * no other value is specified with {@link #setStep(long) setStep()} method.
	 */
	public static final int DEFAULT_PIXEL_COUNT = 600;
	private static final double DEFAULT_PERCENTILE = 95.0; // %

	private int pixelCount = DEFAULT_PIXEL_COUNT;

	/**
	 * Constant that defines the default {@link RrdDbPool} usage policy. Defaults to <code>false</code>
	 * (i.e. the pool will not be used to fetch data from RRD files)
	 */
	public static final boolean DEFAULT_POOL_USAGE_POLICY = false;
	private boolean poolUsed = DEFAULT_POOL_USAGE_POLICY;

	private final long tStart;
	private long tEnd, timestamps[];
	private long lastRrdArchiveUpdateTime = 0;
	// this will be adjusted later
	private long step = 0;
	// resolution to be used for RRD fetch operation
	private long fetchRequestResolution = 1;

	// the order is important, ordinary HashMap is unordered
	private Map<String, Source> sources = new LinkedHashMap<String, Source>();

	private Def[] defSources;

	/**
	 * Creates new DataProcessor object for the given time span. Ending timestamp may be set to zero.
	 * In that case, the class will try to find the optimal ending timestamp based on the last update time of
	 * RRD files processed with the {@link #processData()} method.
	 *
	 * @param t1 Starting timestamp in seconds without milliseconds
	 * @param t2 Ending timestamp in seconds without milliseconds
	 * @throws RrdException Thrown if invalid timestamps are supplied
	 */
	public DataProcessor(long t1, long t2) throws RrdException {
		if ((t1 < t2 && t1 > 0 && t2 > 0) || (t1 > 0 && t2 == 0)) {
			this.tStart = t1;
			this.tEnd = t2;
		}
		else {
			throw new RrdException("Invalid timestamps specified: " + t1 + ", " + t2);
		}
	}

	/**
	 * Creates new DataProcessor object for the given time span. Ending date may be set to null.
	 * In that case, the class will try to find optimal ending date based on the last update time of
	 * RRD files processed with the {@link #processData()} method.
	 *
	 * @param d1 Starting date
	 * @param d2 Ending date
	 * @throws RrdException Thrown if invalid timestamps are supplied
	 */
	public DataProcessor(Date d1, Date d2) throws RrdException {
		this(Util.getTimestamp(d1), d2 != null ? Util.getTimestamp(d2) : 0);
	}

	/**
	 * Creates new DataProcessor object for the given time span. Ending date may be set to null.
	 * In that case, the class will try to find optimal ending date based on the last update time of
	 * RRD files processed with the {@link #processData()} method.
	 *
	 * @param gc1 Starting Calendar date
	 * @param gc2 Ending Calendar date
	 * @throws RrdException Thrown if invalid timestamps are supplied
	 */
	public DataProcessor(Calendar gc1, Calendar gc2) throws RrdException {
		this(Util.getTimestamp(gc1), gc2 != null ? Util.getTimestamp(gc2) : 0);
	}

	/**
	 * Returns boolean value representing {@link org.jrobin.core.RrdDbPool RrdDbPool} usage policy.
	 *
	 * @return true, if the pool will be used internally to fetch data from RRD files, false otherwise.
	 */
	public boolean isPoolUsed() {
		return poolUsed;
	}

	/**
	 * Sets the {@link org.jrobin.core.RrdDbPool RrdDbPool} usage policy.
	 *
	 * @param poolUsed true, if the pool should be used to fetch data from RRD files, false otherwise.
	 */
	public void setPoolUsed(boolean poolUsed) {
		this.poolUsed = poolUsed;
	}

	/**
	 * Sets the number of pixels (target graph width). This number is used only to calculate pixel coordinates
	 * for JRobin graphs (methods {@link #getValuesPerPixel(String)} and {@link #getTimestampsPerPixel()}),
	 * but has influence neither on datasource values calculated with the
	 * {@link #processData()} method nor on aggregated values returned from {@link #getAggregates(String)}
	 * and similar methods. In other words, aggregated values will not change once you decide to change
	 * the dimension of your graph.
	 * <p>
	 * The default number of pixels is defined by constant {@link #DEFAULT_PIXEL_COUNT}
	 * and can be changed with a {@link #setPixelCount(int)} method.
	 *
	 * @param pixelCount The number of pixels. If you process RRD data in order to display it on the graph,
	 *                   this should be the width of your graph.
	 */
	public void setPixelCount(int pixelCount) {
		this.pixelCount = pixelCount;
	}

	/**
	 * Returns the number of pixels (target graph width). See {@link #setPixelCount(int)} for more information.
	 *
	 * @return Target graph width
	 */
	public int getPixelCount() {
		return pixelCount;
	}

	/**
	 * Roughly corresponds to the --step option in RRDTool's graph/xport commands. Here is an explanation borrowed
	 * from RRDTool:
	 * <p>
	 * <i>"By default rrdgraph calculates the width of one pixel in the time
	 * domain and tries to get data at that resolution from the RRD. With
	 * this switch you can override this behavior. If you want rrdgraph to
	 * get data at 1 hour resolution from the RRD, then you can set the
	 * step to 3600 seconds. Note, that a step smaller than 1 pixel will
	 * be silently ignored."</i>
	 * <p>
	 * I think this option is not that useful, but it's here just for compatibility.
	 * <p>
	 * @param step Time step at which data should be fetched from RRD files. If this method is not used,
	 *             the step will be equal to the smallest RRD step of all processed RRD files. If no RRD file is processed,
	 *             the step will be roughly equal to the with of one graph pixel (in seconds).
	 */
	public void setStep(long step) {
		this.step = step;
	}

	/**
	 * Returns the time step used for data processing. Initially, this method returns zero.
	 * Once {@link #processData()} is finished, the method will return the real value used for
	 * all internal computations. Roughly corresponds to the --step option in RRDTool's graph/xport commands.
	 *
	 * @return Step used for data processing.
	 */
	public long getStep() {
		return step;
	}

	/**
	 * Returns desired RRD archive step (reslution) in seconds to be used while fetching data
	 * from RRD files. In other words, this value will used as the last parameter of
	 * {@link RrdDb#createFetchRequest(String, long, long, long) RrdDb.createFetchRequest()} method
	 * when this method is called internally by this DataProcessor.
	 *
	 * @return Desired archive step (fetch resolution) in seconds.
	 */
	public long getFetchRequestResolution() {
		return fetchRequestResolution;
	}

	/**
	 * Sets desired RRD archive step in seconds to be used internally while fetching data
	 * from RRD files. In other words, this value will used as the last parameter of
	 * {@link RrdDb#createFetchRequest(String, long, long, long) RrdDb.createFetchRequest()} method
	 * when this method is called internally by this DataProcessor. If this method is never called, fetch
	 * request resolution defaults to 1 (smallest possible archive step will be chosen automatically).
	 *
	 * @param fetchRequestResolution Desired archive step (fetch resoltuion) in seconds.
	 */
	public void setFetchRequestResolution(long fetchRequestResolution) {
		this.fetchRequestResolution = fetchRequestResolution;
	}

	/**
	 * Returns ending timestamp. Basically, this value is equal to the ending timestamp
	 * specified in the constructor. However, if the ending timestamps was zero, it
	 * will be replaced with the real timestamp when the {@link #processData()} method returns. The real
	 * value will be calculated from the last update times of processed RRD files.
	 *
	 * @return Ending timestamp in seconds
	 */
	public long getEndingTimestamp() {
		return tEnd;
	}

	/**
	 * Returns consolidated timestamps created with the {@link #processData()} method.
	 *
	 * @return array of timestamps in seconds
	 * @throws RrdException thrown if timestamps are not calculated yet
	 */
	public long[] getTimestamps() throws RrdException {
		if (timestamps == null) {
			throw new RrdException("Timestamps not calculated yet");
		}
		else {
			return timestamps;
		}
	}

	/**
	 * Returns calculated values for a single datasource. Corresponding timestamps can be obtained from
	 * the {@link #getTimestamps()} method.
	 *
	 * @param sourceName Datasource name
	 * @return an array of datasource values
	 * @throws RrdException Thrown if invalid datasource name is specified,
	 *                      or if datasource values are not yet calculated (method {@link #processData()}
	 *                      was not called)
	 */
	public double[] getValues(String sourceName) throws RrdException {
		Source source = getSource(sourceName);
		double[] values = source.getValues();
		if (values == null) {
			throw new RrdException("Values not available for source [" + sourceName + "]");
		}
		return values;
	}

	/**
	 * Returns single aggregated value for a single datasource.
	 *
	 * @param sourceName Datasource name
	 * @param consolFun  Consolidation function to be applied to fetched datasource values.
	 *                   Valid consolidation functions are MIN, MAX, LAST, FIRST, AVERAGE and TOTAL
	 *                   (these string constants are conveniently defined in the {@link ConsolFuns} class)
	 * @return MIN, MAX, LAST, FIRST, AVERAGE or TOTAL value calculated from the data
	 *         for the given datasource name
	 * @throws RrdException Thrown if invalid datasource name is specified,
	 *                      or if datasource values are not yet calculated (method {@link #processData()}
	 *                      was not called)
	 */
	public double getAggregate(String sourceName, String consolFun) throws RrdException {
		Source source = getSource(sourceName);
		return source.getAggregates(tStart, tEnd).getAggregate(consolFun);
	}

	/**
	 * Returns all (MIN, MAX, LAST, FIRST, AVERAGE and TOTAL) aggregated values for a single datasource.
	 *
	 * @param sourceName Datasource name
	 * @return Object containing all aggregated values
	 * @throws RrdException Thrown if invalid datasource name is specified,
	 *                      or if datasource values are not yet calculated (method {@link #processData()}
	 *                      was not called)
	 */
	public Aggregates getAggregates(String sourceName) throws RrdException {
		Source source = getSource(sourceName);
		return source.getAggregates(tStart, tEnd);
	}

	/**
	 * This method is just an alias for {@link #getPercentile(String)} method.
	 * <p>
	 * Used by ISPs which charge for bandwidth utilization on a "95th percentile" basis.
	 * <p>
	 * The 95th percentile is the highest source value left when the top 5% of a numerically sorted set
	 * of source data is discarded. It is used as a measure of the peak value used when one discounts
	 * a fair amount for transitory spikes. This makes it markedly different from the average.
	 * <p>
	 * Read more about this topic at
	 * <a href="http://www.red.net/support/resourcecentre/leasedline/percentile.php">Rednet</a> or
	 * <a href="http://www.bytemark.co.uk/support/tech/95thpercentile.html">Bytemark</a>.
	 *
	 * @param sourceName Datasource name
	 * @return 95th percentile of fetched source values
	 * @throws RrdException Thrown if invalid source name is supplied
	 */
	public double get95Percentile(String sourceName) throws RrdException {
		return getPercentile(sourceName);
	}

	/**
	 * Used by ISPs which charge for bandwidth utilization on a "95th percentile" basis.
	 * <p>
	 * The 95th percentile is the highest source value left when the top 5% of a numerically sorted set
	 * of source data is discarded. It is used as a measure of the peak value used when one discounts
	 * a fair amount for transitory spikes. This makes it markedly different from the average.
	 * <p>
	 * Read more about this topic at
	 * <a href="http://www.red.net/support/resourcecentre/leasedline/percentile.php">Rednet</a> or
	 * <a href="http://www.bytemark.co.uk/support/tech/95thpercentile.html">Bytemark</a>.
	 *
	 * @param sourceName Datasource name
	 * @return 95th percentile of fetched source values
	 * @throws RrdException Thrown if invalid source name is supplied
	 */
	public double getPercentile(String sourceName) throws RrdException {
		return getPercentile(sourceName, DEFAULT_PERCENTILE);
	}

	/**
	 * The same as {@link #getPercentile(String)} but with a possibility to define custom percentile boundary
	 * (different from 95).
	 *
	 * @param sourceName Datasource name.
	 * @param percentile Boundary percentile. Value of 95 (%) is suitable in most cases, but you are free
	 *                   to provide your own percentile boundary between zero and 100.
	 * @return Requested percentile of fetched source values
	 * @throws RrdException Thrown if invalid sourcename is supplied, or if the percentile value makes no sense.
	 */
	public double getPercentile(String sourceName, double percentile) throws RrdException {
		if (percentile <= 0.0 || percentile > 100.0) {
			throw new RrdException("Invalid percentile [" + percentile + "], should be between 0 and 100");
		}
		Source source = getSource(sourceName);
		return source.getPercentile(tStart, tEnd, percentile);
	}

	/**
	 * Returns array of datasource names defined in this DataProcessor.
	 *
	 * @return array of datasource names
	 */
	public String[] getSourceNames() {
		return sources.keySet().toArray(new String[0]);
	}

	/**
	 * Returns an array of all datasource values for all datasources. Each row in this two-dimensional
	 * array represents an array of calculated values for a single datasource. The order of rows is the same
	 * as the order in which datasources were added to this DataProcessor object.
	 *
	 * @return All datasource values for all datasources. The first index is the index of the datasource,
	 *         the second index is the index of the datasource value. The number of datasource values is equal
	 *         to the number of timestamps returned with {@link #getTimestamps()}  method.
	 * @throws RrdException Thrown if invalid datasource name is specified,
	 *                      or if datasource values are not yet calculated (method {@link #processData()}
	 *                      was not called)
	 */
	public double[][] getValues() throws RrdException {
		String[] names = getSourceNames();
		double[][] values = new double[names.length][];
		for (int i = 0; i < names.length; i++) {
			values[i] = getValues(names[i]);
		}
		return values;
	}

	private Source getSource(String sourceName) throws RrdException {
		Source source = sources.get(sourceName);
		if (source != null) {
			return source;
		}
		throw new RrdException("Unknown source: " + sourceName);
	}

	/////////////////////////////////////////////////////////////////
	// DATASOURCE DEFINITIONS
	/////////////////////////////////////////////////////////////////

	/**
	 * Adds a custom, {@link org.jrobin.data.Plottable plottable} datasource (<b>PDEF</b>).
	 * The datapoints should be made available by a class extending
	 * {@link org.jrobin.data.Plottable Plottable} class.
	 * <p>
	 *
	 * @param name	  source name.
	 * @param plottable class that extends Plottable class and is suited for graphing.
	 */
	public void addDatasource(String name, Plottable plottable) {
		PDef pDef = new PDef(name, plottable);
		sources.put(name, pDef);
	}

	/**
	 * Adds complex source (<b>CDEF</b>).
	 * Complex sources are evaluated using the supplied <code>RPN</code> expression.
	 * <p>
	 * Complex source <code>name</code> can be used:
	 * <ul>
	 * <li>To specify sources for line, area and stack plots.</li>
	 * <li>To define other complex sources.</li>
	 * </ul>
	 * <p>
	 * JRobin supports the following RPN functions, operators and constants: +, -, *, /,
	 * %, SIN, COS, LOG, EXP, FLOOR, CEIL, ROUND, POW, ABS, SQRT, RANDOM, LT, LE, GT, GE, EQ,
	 * IF, MIN, MAX, LIMIT, DUP, EXC, POP, UN, UNKN, NOW, TIME, PI, E,
	 * AND, OR, XOR, PREV, PREV(sourceName), INF, NEGINF, STEP, YEAR, MONTH, DATE,
	 * HOUR, MINUTE, SECOND, WEEK, SIGN and RND.
	 * <p>
	 * JRobin does not force you to specify at least one simple source name as RRDTool.
	 * <p>
	 * For more details on RPN see RRDTool's
	 * <a href="http://people.ee.ethz.ch/~oetiker/webtools/rrdtool/manual/rrdgraph.html" target="man">
	 * rrdgraph man page</a>.
	 *
	 * @param name		  source name.
	 * @param rpnExpression RPN expression containig comma (or space) delimited simple and complex
	 *                      source names, RPN constants, functions and operators.
	 */
	public void addDatasource(String name, String rpnExpression) {
		CDef cDef = new CDef(name, rpnExpression);
		sources.put(name, cDef);
	}

	/**
	 * Adds static source (<b>SDEF</b>). Static sources are the result of a consolidation function applied
	 * to *any* other source that has been defined previously.
	 *
	 * @param name	  source name.
	 * @param defName   Name of the datasource to calculate the value from.
	 * @param consolFun Consolidation function to use for value calculation
	 */
	public void addDatasource(String name, String defName, String consolFun) {
		SDef sDef = new SDef(name, defName, consolFun);
		sources.put(name, sDef);
	}

	/**
	 * <p>Adds simple datasource (<b>DEF</b>). Simple source <code>name</code>
	 * can be used:</p>
	 * <ul>
	 * <li>To specify sources for line, area and stack plots.</li>
	 * <li>To define complex sources
	 * </ul>
	 *
	 * @param name	   source name.
	 * @param file	   Path to RRD file.
	 * @param dsName	 Datasource name defined in the RRD file.
	 * @param consolFunc Consolidation function that will be used to extract data from the RRD
	 *                   file ("AVERAGE", "MIN", "MAX" or "LAST" - these string constants are conveniently defined
	 *                   in the {@link org.jrobin.core.ConsolFuns ConsolFuns} class).
	 */
	public void addDatasource(String name, String file, String dsName, String consolFunc) {
		Def def = new Def(name, file, dsName, consolFunc);
		sources.put(name, def);
	}

	/**
	 * <p>Adds simple source (<b>DEF</b>). Source <code>name</code> can be used:</p>
	 * <ul>
	 * <li>To specify sources for line, area and stack plots.</li>
	 * <li>To define complex sources
	 * </ul>
	 *
	 * @param name	   Source name.
	 * @param file	   Path to RRD file.
	 * @param dsName	 Data source name defined in the RRD file.
	 * @param consolFunc Consolidation function that will be used to extract data from the RRD
	 *                   file ("AVERAGE", "MIN", "MAX" or "LAST" - these string constants are conveniently defined
	 *                   in the {@link org.jrobin.core.ConsolFuns ConsolFuns} class).
	 * @param backend	Name of the RrdBackendFactory that should be used for this RrdDb.
	 */
	public void addDatasource(String name, String file, String dsName, String consolFunc, String backend) {
		Def def = new Def(name, file, dsName, consolFunc, backend);
		sources.put(name, def);
	}

	/**
	 * Adds DEF datasource with datasource values already available in the FetchData object. This method is
	 * used internally by JRobin and probably has no purpose outside of it.
	 *
	 * @param name	  Source name.
	 * @param fetchData Fetched data containing values for the given source name.
	 */
	public void addDatasource(String name, FetchData fetchData) {
		Def def = new Def(name, fetchData);
		sources.put(name, def);
	}

	/**
         * Creates a new VDEF datasource that performs a percentile calculation on an
         * another named datasource to yield a single value.
         *
         * Requires that the other datasource has already been defined; otherwise, it'll
         * end up with no data
         *
         * @param name - the new virtual datasource name
         * @param sourceName - the datasource from which to extract the percentile.  Must be a previously
         *                     defined virtual datasource
         * @param percentile - the percentile to extract from the source datasource
         */
	public void addDatasource(String name, String sourceName, double percentile) {
	    Source source = sources.get(sourceName);
	    sources.put(name, new PercentileDef(name, source, percentile));
	}

	/**
         * Creates a new VDEF datasource that performs a percentile calculation on an
         * another named datasource to yield a single value.
         *
         * Requires that the other datasource has already been defined; otherwise, it'll
         * end up with no data
         *
         * @param name - the new virtual datasource name
         * @param sourceName - the datasource from which to extract the percentile.  Must be a previously
         *                     defined virtual datasource
         * @param percentile - the percentile to extract from the source datasource
         * @param ignorenan - true if we include Double.NaN
         */
	public void addDatasource(String name, String sourceName, double percentile, boolean ignorenan) {
	    Source source = sources.get(sourceName);
	    sources.put(name, new PercentileDef(name, source, percentile, ignorenan));
	}

	/////////////////////////////////////////////////////////////////
	// CALCULATIONS
	/////////////////////////////////////////////////////////////////

	/**
	 * Method that should be called once all datasources are defined. Data will be fetched from
	 * RRD files, RPN expressions will be calculated, etc.
	 *
	 * @throws IOException  Thrown in case of I/O error (while fetching data from RRD files)
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public void processData() throws IOException, RrdException {
		extractDefs();
		fetchRrdData();
		fixZeroEndingTimestamp();
		chooseOptimalStep();
		createTimestamps();
		assignTimestampsToSources();
		normalizeRrdValues();
		calculateNonRrdSources();
	}

	/**
	 * Method used to calculate datasource values which should be presented on the graph
	 * based on the desired graph width. Each value returned represents a single pixel on the graph.
	 * Corresponding timestamp can be found in the array returned from {@link #getTimestampsPerPixel()}
	 * method.
	 *
	 * @param sourceName Datasource name
	 * @param pixelCount Graph width
	 * @return Per-pixel datasource values
	 * @throws RrdException Thrown if datasource values are not yet calculated (method {@link #processData()}
	 *                      was not called)
	 */
	public double[] getValuesPerPixel(String sourceName, int pixelCount) throws RrdException {
		setPixelCount(pixelCount);
		return getValuesPerPixel(sourceName);
	}

	/**
	 * Method used to calculate datasource values which should be presented on the graph
	 * based on the graph width set with a {@link #setPixelCount(int)} method call.
	 * Each value returned represents a single pixel on the graph. Corresponding timestamp can be
	 * found in the array returned from {@link #getTimestampsPerPixel()} method.
	 *
	 * @param sourceName Datasource name
	 * @return Per-pixel datasource values
	 * @throws RrdException Thrown if datasource values are not yet calculated (method {@link #processData()}
	 *                      was not called)
	 */
	public double[] getValuesPerPixel(String sourceName) throws RrdException {
		double[] values = getValues(sourceName);
		double[] pixelValues = new double[pixelCount];
		Arrays.fill(pixelValues, Double.NaN);
		long span = tEnd - tStart;
		// this is the ugliest nested loop I have ever made
		for (int pix = 0, ref = 0; pix < pixelCount; pix++) {
			double t = tStart + (double) (span * pix) / (double) (pixelCount - 1);
			while (ref < timestamps.length) {
				if (t <= timestamps[ref] - step) {
					// too left, nothing to do, already NaN
					break;
				}
				else if (t <= timestamps[ref]) {
					// in brackets, get this value
					pixelValues[pix] = values[ref];
					break;
				}
				else {
					// too right
					ref++;
				}
			}
		}
		return pixelValues;
	}

	/**
	 * Calculates timestamps which correspond to individual pixels on the graph.
	 *
	 * @param pixelCount Graph width
	 * @return Array of timestamps
	 */
	public long[] getTimestampsPerPixel(int pixelCount) {
		setPixelCount(pixelCount);
		return getTimestampsPerPixel();
	}

	/**
	 * Calculates timestamps which correspond to individual pixels on the graph
	 * based on the graph width set with a {@link #setPixelCount(int)} method call.
	 *
	 * @return Array of timestamps
	 */
	public long[] getTimestampsPerPixel() {
		long[] times = new long[pixelCount];
		long span = tEnd - tStart;
		for (int i = 0; i < pixelCount; i++) {
			times[i] = Math.round(tStart + (double) (span * i) / (double) (pixelCount - 1));
		}
		return times;
	}

	/**
	 * Dumps timestamps and values of all datasources in a tabelar form. Very useful for debugging.
	 *
	 * @return Dumped object content.
	 * @throws RrdException Thrown if nothing is calculated so far (the method {@link #processData()}
	 *                      was not called).
	 */
	public String dump() throws RrdException {
		String[] names = getSourceNames();
		double[][] values = getValues();
		StringBuilder buffer = new StringBuilder();
		buffer.append(format("timestamp", 12));
		for (String name : names) {
			buffer.append(format(name, 20));
		}
		buffer.append("\n");
		for (int i = 0; i < timestamps.length; i++) {
			buffer.append(format("" + timestamps[i], 12));
			for (int j = 0; j < names.length; j++) {
				buffer.append(format(Util.formatDouble(values[j][i]), 20));
			}
			buffer.append("\n");
		}
		return buffer.toString();
	}

	/**
	 * Returns time when last RRD archive was updated (all RRD files are considered).
	 *
	 * @return Last archive update time for all RRD files in this DataProcessor
	 */
	public long getLastRrdArchiveUpdateTime() {
		return lastRrdArchiveUpdateTime;
	}

	// PRIVATE METHODS

	private void extractDefs() {
		List<Def> defList = new ArrayList<Def>();
		for (Source source : sources.values()) {
			if (source instanceof Def) {
				defList.add((Def) source);
			}
		}
		defSources = defList.toArray(new Def[defList.size()]);
	}

	private void fetchRrdData() throws IOException, RrdException {
		long tEndFixed = (tEnd == 0) ? Util.getTime() : tEnd;
		for (int i = 0; i < defSources.length; i++) {
			if (!defSources[i].isLoaded()) {
				// not fetched yet
				Set<String> dsNames = new HashSet<String>();
				dsNames.add(defSources[i].getDsName());
				// look for all other datasources with the same path and the same consolidation function
				for (int j = i + 1; j < defSources.length; j++) {
					if (defSources[i].isCompatibleWith(defSources[j])) {
						dsNames.add(defSources[j].getDsName());
					}
				}
				// now we have everything
				RrdDb rrd = null;
				try {
					rrd = getRrd(defSources[i]);
					lastRrdArchiveUpdateTime = Math.max(lastRrdArchiveUpdateTime, rrd.getLastArchiveUpdateTime());
					FetchRequest req = rrd.createFetchRequest(defSources[i].getConsolFun(),
							tStart, tEndFixed, fetchRequestResolution);
					req.setFilter(dsNames);
					FetchData data = req.fetchData();
					defSources[i].setFetchData(data);
					for (int j = i + 1; j < defSources.length; j++) {
						if (defSources[i].isCompatibleWith(defSources[j])) {
							defSources[j].setFetchData(data);
						}
					}
				}
				finally {
					if (rrd != null) {
						releaseRrd(rrd, defSources[i]);
					}
				}
			}
		}
	}

	private void fixZeroEndingTimestamp() throws RrdException {
		if (tEnd == 0) {
			if (defSources.length == 0) {
				throw new RrdException("Could not adjust zero ending timestamp, no DEF source provided");
			}
			tEnd = defSources[0].getArchiveEndTime();
			for (int i = 1; i < defSources.length; i++) {
				tEnd = Math.min(tEnd, defSources[i].getArchiveEndTime());
			}
			if (tEnd <= tStart) {
				throw new RrdException("Could not resolve zero ending timestamp.");
			}
		}
	}

	// Tricky and ugly. Should be redesigned some time in the future
	private void chooseOptimalStep() {
		long newStep = Long.MAX_VALUE;
		for (Def defSource : defSources) {
			long fetchStep = defSource.getFetchStep(), tryStep = fetchStep;
			if (step > 0) {
				tryStep = Math.min(newStep, (((step - 1) / fetchStep) + 1) * fetchStep);
			}
			newStep = Math.min(newStep, tryStep);
		}
		if (newStep != Long.MAX_VALUE) {
			// step resolved from a RRD file
			step = newStep;
		}
		else {
			// choose step based on the number of pixels (useful for plottable datasources)
			step = Math.max((tEnd - tStart) / pixelCount, 1);
		}
	}

	private void createTimestamps() {
		long t1 = Util.normalize(tStart, step);
		long t2 = Util.normalize(tEnd, step);
                if (t2 < tEnd) {
                    t2 += step;
                }
		int count = (int) (((t2 - t1) / step) + 1);
		timestamps = new long[count];
		for (int i = 0; i < count; i++) {
			timestamps[i] = t1;
			t1 += step;
		}
	}

	private void assignTimestampsToSources() {
		for (Source src : sources.values()) {
			src.setTimestamps(timestamps);
		}
	}

	private void normalizeRrdValues() throws RrdException {
		Normalizer normalizer = new Normalizer(timestamps);
		for (Def def : defSources) {
			long[] rrdTimestamps = def.getRrdTimestamps();
			double[] rrdValues = def.getRrdValues();
			double[] values = normalizer.normalize(rrdTimestamps, rrdValues);
			def.setValues(values);
		}
	}

	private void calculateNonRrdSources() throws RrdException {
		for (Source source : sources.values()) {
			if (source instanceof SDef) {
				calculateSDef((SDef) source);
			}
			else if (source instanceof CDef) {
				calculateCDef((CDef) source);
			}
			else if (source instanceof PDef) {
				calculatePDef((PDef) source);
			}
			else if (source instanceof PercentileDef) {
			        calculatePercentileDef((PercentileDef) source);
			}
		}
	}

	private void calculatePDef(PDef pdef) {
		pdef.calculateValues();
	}

	private void calculateCDef(CDef cDef) throws RrdException {
		RpnCalculator calc = new RpnCalculator(cDef.getRpnExpression(), cDef.getName(), this);
		cDef.setValues(calc.calculateValues());
	}

	private void calculateSDef(SDef sDef) throws RrdException {
		String defName = sDef.getDefName();
		String consolFun = sDef.getConsolFun();
		Source source = getSource(defName);
                if (consolFun.equals("MAXIMUM")) { consolFun = "MAX"; }
                else if (consolFun.equals("MINIMUM")) { consolFun = "MIN"; }
		double value = source.getAggregates(tStart, tEnd).getAggregate(consolFun);
		sDef.setValue(value);
	}

	//Yeah, this is different from the other calculation methods
	// Frankly, this is how it *should* be done, and the other methods will
	// be refactored to this design (and the instanceof's removed) at some point
        private void calculatePercentileDef(PercentileDef def) throws RrdException {
                def.calculate(tStart, tEnd);
        }


	private RrdDb getRrd(Def def) throws IOException, RrdException {
		String path = def.getPath(), backend = def.getBackend();
		if (poolUsed && backend == null) {
			return RrdDbPool.getInstance().requestRrdDb(path);
		}
		else if (backend != null) {
			return new RrdDb(path, true, RrdBackendFactory.getFactory(backend));
		}
		else {
			return new RrdDb(path, true);
		}
	}

	private void releaseRrd(RrdDb rrd, Def def) throws IOException, RrdException {
		String backend = def.getBackend();
		if (poolUsed && backend == null) {
			RrdDbPool.getInstance().release(rrd);
		}
		else {
			rrd.close();
		}
	}

	private static String format(String s, int length) {
		StringBuilder b = new StringBuilder(s);
		for (int i = 0; i < length - s.length(); i++) {
			b.append(' ');
		}
		return b.toString();
	}

	/**
	 * Cute little demo. Uses demo.rrd file previously created by basic JRobin demo.
	 *
	 * @param args Not used
	 * @throws IOException if an I/O error occurs.
	 * @throws RrdException Thrown if internal jrobin error occurs.
	 */
	public static void main(String[] args) throws IOException, RrdException {
		// time span
		long t1 = Util.getTimestamp(2003, 4, 1);
		long t2 = Util.getTimestamp(2003, 5, 1);
		System.out.println("t1 = " + t1);
		System.out.println("t2 = " + t2);

		// RRD file to use
		String rrdPath = Util.getJRobinDemoPath("demo.rrd");

		// constructor
		DataProcessor dp = new DataProcessor(t1, t2);

		// uncomment and run again
		//dp.setFetchRequestResolution(86400);

		// uncomment and run again
		//dp.setStep(86500);

		// datasource definitions
		dp.addDatasource("X", rrdPath, "sun", "AVERAGE");
		dp.addDatasource("Y", rrdPath, "shade", "AVERAGE");
		dp.addDatasource("Z", "X,Y,+,2,/");
		dp.addDatasource("DERIVE[Z]", "Z,PREV(Z),-,STEP,/");
		dp.addDatasource("TREND[Z]", "DERIVE[Z],SIGN");
		dp.addDatasource("AVG[Z]", "Z", "AVERAGE");
		dp.addDatasource("DELTA", "Z,AVG[Z],-");

		// action
		long laptime = System.currentTimeMillis();
		//dp.setStep(86400);
		dp.processData();
		System.out.println("Data processed in " + (System.currentTimeMillis() - laptime) + " milliseconds\n---");
		System.out.println(dp.dump());

		// aggregates
		System.out.println("\nAggregates for X");
		Aggregates agg = dp.getAggregates("X");
		System.out.println(agg.dump());
		System.out.println("\nAggregates for Y");
		agg = dp.getAggregates("Y");
		System.out.println(agg.dump());

		// 95-percentile
		System.out.println("\n95-percentile for X: " + Util.formatDouble(dp.get95Percentile("X")));
		System.out.println("95-percentile for Y: " + Util.formatDouble(dp.get95Percentile("Y")));

		// lastArchiveUpdateTime
		System.out.println("\nLast archive update time was: " + dp.getLastRrdArchiveUpdateTime());
	}
}
