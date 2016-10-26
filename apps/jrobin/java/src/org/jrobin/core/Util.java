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

import org.jrobin.core.timespec.TimeParser;
import org.jrobin.core.timespec.TimeSpec;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.io.*;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Class defines various utility functions used in JRobin.
 *
 * @author <a href="mailto:saxon@jrobin.org">Sasa Markovic</a>
 */
public class Util {

	public static final long MAX_LONG = Long.MAX_VALUE;
	public static final long MIN_LONG = -Long.MAX_VALUE;

	public static final double MAX_DOUBLE = Double.MAX_VALUE;
	public static final double MIN_DOUBLE = -Double.MAX_VALUE;

	// pattern RRDTool uses to format doubles in XML files
	static final String PATTERN = "0.0000000000E00";
	// directory under $USER_HOME used for demo graphs storing
	static final String JROBIN_DIR = "jrobin-demo";

	static final DecimalFormat df;

	static {
		df = (DecimalFormat) NumberFormat.getNumberInstance(Locale.ENGLISH);
		df.applyPattern(PATTERN);
		df.setPositivePrefix("+");
	}

	/**
	 * Converts an array of long primitives to an array of doubles.
	 *
	 * @param array input array of long values.
	 * @return Same array but with all values as double.
	 */
	public static double[] toDoubleArray(final long[] array) {
		double[] values = new double[ array.length ];
		for (int i = 0; i < array.length; i++) {
			values[i] = array[i];
		}
		return values;
	}

	/**
	 * Returns current timestamp in seconds (without milliseconds). Returned timestamp
	 * is obtained with the following expression:
	 * <p>
	 * <code>(System.currentTimeMillis() + 500L) / 1000L</code>
	 *
	 * @return Current timestamp
	 */
	public static long getTime() {
		return (System.currentTimeMillis() + 500L) / 1000L;
	}

	/**
	 * Just an alias for {@link #getTime()} method.
	 *
	 * @return Current timestamp (without milliseconds)
	 */
	public static long getTimestamp() {
		return getTime();
	}

	/**
	 * Rounds the given timestamp to the nearest whole &quot;step&quot;. Rounded value is obtained
	 * from the following expression:
	 * <p>
	 * <code>timestamp - timestamp % step;</code>
	 *
	 * @param timestamp Timestamp in seconds
	 * @param step	  Step in seconds
	 * @return "Rounded" timestamp
	 */
	public static long normalize(long timestamp, long step) {
		return timestamp - timestamp % step;
	}

	/**
	 * Returns the greater of two double values, but treats NaN as the smallest possible
	 * value. Note that <code>Math.max()</code> behaves differently for NaN arguments.
	 *
	 * @param x an argument
	 * @param y another argument
	 * @return the lager of arguments
	 */
	public static double max(double x, double y) {
		return Double.isNaN(x) ? y : Double.isNaN(y) ? x : Math.max(x, y);
	}

	/**
	 * Returns the smaller of two double values, but treats NaN as the greatest possible
	 * value. Note that <code>Math.min()</code> behaves differently for NaN arguments.
	 *
	 * @param x an argument
	 * @param y another argument
	 * @return the smaller of arguments
	 */
	public static double min(double x, double y) {
		return Double.isNaN(x) ? y : Double.isNaN(y) ? x : Math.min(x, y);
	}

	/**
	 * Calculates sum of two doubles, but treats NaNs as zeros.
	 *
	 * @param x First double
	 * @param y Second double
	 * @return Sum(x,y) calculated as <code>Double.isNaN(x)? y: Double.isNaN(y)? x: x + y;</code>
	 */
	public static double sum(double x, double y) {
		return Double.isNaN(x) ? y : Double.isNaN(y) ? x : x + y;
	}

	static String formatDouble(double x, String nanString, boolean forceExponents) {
		if (Double.isNaN(x)) {
			return nanString;
		}
		if (forceExponents) {
			return df.format(x);
		}
		return "" + x;
	}

	static String formatDouble(double x, boolean forceExponents) {
		return formatDouble(x, "" + Double.NaN, forceExponents);
	}

	/**
	 * Formats double as a string using exponential notation (RRDTool like). Used for debugging
	 * throught the project.
	 *
	 * @param x value to be formatted
	 * @return string like "+1.234567E+02"
	 */
	public static String formatDouble(double x) {
		return formatDouble(x, true);
	}

	/**
	 * Returns <code>Date</code> object for the given timestamp (in seconds, without
	 * milliseconds)
	 *
	 * @param timestamp Timestamp in seconds.
	 * @return Corresponding Date object.
	 */
	public static Date getDate(long timestamp) {
		return new Date(timestamp * 1000L);
	}

	/**
	 * Returns <code>Calendar</code> object for the given timestamp
	 * (in seconds, without milliseconds)
	 *
	 * @param timestamp Timestamp in seconds.
	 * @return Corresponding Calendar object.
	 */
	public static Calendar getCalendar(long timestamp) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTimeInMillis(timestamp * 1000L);
		return calendar;
	}

	/**
	 * Returns <code>Calendar</code> object for the given Date object
	 *
	 * @param date Date object
	 * @return Corresponding Calendar object.
	 */
	public static Calendar getCalendar(Date date) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(date);
		return calendar;
	}

	/**
	 * Returns timestamp (unix epoch) for the given Date object
	 *
	 * @param date Date object
	 * @return Corresponding timestamp (without milliseconds)
	 */
	public static long getTimestamp(final Date date) {
		// round to whole seconds, ignore milliseconds
		return (date.getTime() + 499L) / 1000L;
	}

	/**
	 * Returns timestamp (unix epoch) for the given Calendar object
	 *
	 * @param gc Calendar object
	 * @return Corresponding timestamp (without milliseconds)
	 */
	public static long getTimestamp(final Calendar gc) {
		return getTimestamp(gc.getTime());
	}

	/**
	 * Returns timestamp (unix epoch) for the given year, month, day, hour and minute.
	 *
	 * @param year  Year
	 * @param month Month (zero-based)
	 * @param day   Day in month
	 * @param hour  Hour
	 * @param min   Minute
	 * @return Corresponding timestamp
	 */
	public static long getTimestamp(final int year, final int month, final int day, final int hour, final int min) {
	    final Calendar calendar = Calendar.getInstance();
		calendar.clear();
		calendar.set(year, month, day, hour, min);
		return Util.getTimestamp(calendar);
	}

	/**
	 * Returns timestamp (unix epoch) for the given year, month and day.
	 *
	 * @param year  Year
	 * @param month Month (zero-based)
	 * @param day   Day in month
	 * @return Corresponding timestamp
	 */
	public static long getTimestamp(int year, int month, int day) {
		return Util.getTimestamp(year, month, day, 0, 0);
	}

	/**
	 * Parses at-style time specification and returns the corresponding timestamp. For example:<p>
	 * <pre>
	 * long t = Util.getTimestamp("now-1d");
	 * </pre>
	 *
	 * @param atStyleTimeSpec at-style time specification. For the complete explanation of the syntax
	 *                        allowed see RRDTool's <code>rrdfetch</code> man page.<p>
	 * @return timestamp in seconds since epoch.
	 * @throws RrdException Thrown if invalid time specification is supplied.
	 */
	public static long getTimestamp(final String atStyleTimeSpec) throws RrdException {
	    final TimeSpec timeSpec = new TimeParser(atStyleTimeSpec).parse();
		return timeSpec.getTimestamp();
	}

	/**
	 * Parses two related at-style time specifications and returns corresponding timestamps. For example:<p>
	 * <pre>
	 * long[] t = Util.getTimestamps("end-1d","now");
	 * </pre>
	 *
	 * @param atStyleTimeSpec1 Starting at-style time specification. For the complete explanation of the syntax
	 *                         allowed see RRDTool's <code>rrdfetch</code> man page.<p>
	 * @param atStyleTimeSpec2 Ending at-style time specification. For the complete explanation of the syntax
	 *                         allowed see RRDTool's <code>rrdfetch</code> man page.<p>
	 * @return An array of two longs representing starting and ending timestamp in seconds since epoch.
	 * @throws RrdException Thrown if any input time specification is invalid.
	 */
	public static long[] getTimestamps(final String atStyleTimeSpec1, final String atStyleTimeSpec2) throws RrdException {
	    final TimeSpec timeSpec1 = new TimeParser(atStyleTimeSpec1).parse();
		final TimeSpec timeSpec2 = new TimeParser(atStyleTimeSpec2).parse();
		return TimeSpec.getTimestamps(timeSpec1, timeSpec2);
	}

	/**
	 * Parses input string as a double value. If the value cannot be parsed, Double.NaN
	 * is returned (NumberFormatException is never thrown).
	 *
	 * @param valueStr String representing double value
	 * @return a double corresponding to the input string
	 */
	public static double parseDouble(final String valueStr) {
		double value;
		try {
			value = Double.parseDouble(valueStr);
		}
		catch (final NumberFormatException nfe) {
			value = Double.NaN;
		}
		return value;
	}

	/**
	 * Checks if a string can be parsed as double.
	 *
	 * @param s Input string
	 * @return <code>true</code> if the string can be parsed as double, <code>false</code> otherwise
	 */
	public static boolean isDouble(final String s) {
		try {
			Double.parseDouble(s);
			return true;
		}
		catch (final NumberFormatException nfe) {
			return false;
		}
	}

	/**
	 * Parses input string as a boolean value. The parser is case insensitive.
	 *
	 * @param valueStr String representing boolean value
	 * @return <code>true</code>, if valueStr equals to 'true', 'on', 'yes', 'y' or '1';
	 *         <code>false</code> in all other cases.
	 */
	public static boolean parseBoolean(final String valueStr) {
		return valueStr.equalsIgnoreCase("true") ||
				valueStr.equalsIgnoreCase("on") ||
				valueStr.equalsIgnoreCase("yes") ||
				valueStr.equalsIgnoreCase("y") ||
				valueStr.equalsIgnoreCase("1");
	}

	/**
	 * Parses input string as color. The color string should be of the form #RRGGBB (no alpha specified,
	 * opaque color) or #RRGGBBAA (alpa specified, transparent colors). Leading character '#' is
	 * optional.
	 *
	 * @param valueStr Input string, for example #FFAA24, #AABBCC33, 010203 or ABC13E4F
	 * @return Paint object
	 * @throws RrdException If the input string is not 6 or 8 characters long (without optional '#')
	 */
	public static Paint parseColor(final String valueStr) throws RrdException {
	    final String c = valueStr.startsWith("#") ? valueStr.substring(1) : valueStr;
		if (c.length() != 6 && c.length() != 8) {
			throw new RrdException("Invalid color specification: " + valueStr);
		}
		final String r = c.substring(0, 2), g = c.substring(2, 4), b = c.substring(4, 6);
		if (c.length() == 6) {
			return new Color(Integer.parseInt(r, 16), Integer.parseInt(g, 16), Integer.parseInt(b, 16));
		}
		else {
		    final String a = c.substring(6);
			return new Color(Integer.parseInt(r, 16), Integer.parseInt(g, 16),
					Integer.parseInt(b, 16), Integer.parseInt(a, 16));
		}
	}

	/**
	 * Returns file system separator string.
	 *
	 * @return File system separator ("/" on Unix, "\" on Windows)
	 */
	public static String getFileSeparator() {
		return System.getProperty("file.separator");
	}

	/**
	 * Returns path to user's home directory.
	 *
	 * @return Path to users home directory, with file separator appended.
	 */
	public static String getUserHomeDirectory() {
		return System.getProperty("user.home") + getFileSeparator();
	}

	/**
	 * Returns path to directory used for placement of JRobin demo graphs and creates it
	 * if necessary.
	 *
	 * @return Path to demo directory (defaults to $HOME/jrobin/) if directory exists or
	 *         was successfully created. Null if such directory could not be created.
	 */
	public static String getJRobinDemoDirectory() {
		final String homeDirPath = getUserHomeDirectory() + JROBIN_DIR + getFileSeparator();
		final File homeDirFile = new File(homeDirPath);
		return (homeDirFile.exists() || homeDirFile.mkdirs()) ? homeDirPath : null;
	}

	/**
	 * Returns full path to the file stored in the demo directory of JRobin
	 *
	 * @param filename Partial path to the file stored in the demo directory of JRobin
	 *                 (just name and extension, without parent directories)
	 * @return Full path to the file
	 */
	public static String getJRobinDemoPath(final String filename) {
		final String demoDir = getJRobinDemoDirectory();
		if (demoDir != null) {
			return demoDir + filename;
		}
		else {
			return null;
		}
	}

	static boolean sameFilePath(final String path1, final String path2) throws IOException {
	    final File file1 = new File(path1);
		final File file2 = new File(path2);
		return file1.getCanonicalPath().equals(file2.getCanonicalPath());
	}

	static int getMatchingDatasourceIndex(final RrdDb rrd1, final int dsIndex, final RrdDb rrd2) throws IOException {
	    final String dsName = rrd1.getDatasource(dsIndex).getDsName();
		try {
			return rrd2.getDsIndex(dsName);
		}
		catch (final RrdException e) {
			return -1;
		}
	}

	static int getMatchingArchiveIndex(final RrdDb rrd1, final int arcIndex, final RrdDb rrd2) throws IOException {
	    final Archive archive = rrd1.getArchive(arcIndex);
		final String consolFun = archive.getConsolFun();
		final int steps = archive.getSteps();
		try {
			return rrd2.getArcIndex(consolFun, steps);
		}
		catch (final RrdException e) {
			return -1;
		}
	}

	static String getTmpFilename() throws IOException {
		return File.createTempFile("JROBIN_", ".tmp").getCanonicalPath();
	}

	static final String ISO_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";   // ISO

	/**
	 * Creates Calendar object from a string. The string should represent
	 * either a long integer (UNIX timestamp in seconds without milliseconds,
	 * like "1002354657") or a human readable date string in the format "yyyy-MM-dd HH:mm:ss"
	 * (like "2004-02-25 12:23:45").
	 *
	 * @param timeStr Input string
	 * @return Calendar object
	 */
	public static Calendar getCalendar(final String timeStr) {
		// try to parse it as long
		try {
		    return Util.getCalendar(Long.parseLong(timeStr));
		}
		catch (final NumberFormatException nfe) {
			// not a long timestamp, try to parse it as data
		    final SimpleDateFormat df = new SimpleDateFormat(ISO_DATE_FORMAT);
			df.setLenient(false);
			try {
			    return Util.getCalendar(df.parse(timeStr));
			}
			catch (final ParseException pe) {
				throw new IllegalArgumentException("Time/date not in " + ISO_DATE_FORMAT + " format: " + timeStr);
			}
		}
	}

	/**
	 * Various DOM utility functions
	 */
	public static class Xml {
		public static Node[] getChildNodes(final Node parentNode) {
			return getChildNodes(parentNode, null);
		}

		public static Node[] getChildNodes(final Node parentNode, final String childName) {
		    final ArrayList<Node> nodes = new ArrayList<Node>();
			final NodeList nodeList = parentNode.getChildNodes();
			for (int i = 0; i < nodeList.getLength(); i++) {
				final Node node = nodeList.item(i);
				if (childName == null || node.getNodeName().equals(childName)) {
					nodes.add(node);
				}
			}
			return nodes.toArray(new Node[0]);
		}

		public static Node getFirstChildNode(final Node parentNode, final String childName) throws RrdException {
		    final Node[] childs = getChildNodes(parentNode, childName);
			if (childs.length > 0) {
				return childs[0];
			}
			throw new RrdException("XML Error, no such child: " + childName);
		}

		public static boolean hasChildNode(final Node parentNode, final String childName) {
		    final Node[] childs = getChildNodes(parentNode, childName);
			return childs.length > 0;
		}

		// -- Wrapper around getChildValue with trim
		public static String getChildValue(final Node parentNode, final String childName) throws RrdException {
			return getChildValue(parentNode, childName, true);
		}

		public static String getChildValue(final Node parentNode, final String childName, final boolean trim) throws RrdException {
		    final NodeList children = parentNode.getChildNodes();
			for (int i = 0; i < children.getLength(); i++) {
				final Node child = children.item(i);
				if (child.getNodeName().equals(childName)) {
					return getValue(child, trim);
				}
			}
			throw new RrdException("XML Error, no such child: " + childName);
		}

		// -- Wrapper around getValue with trim
		public static String getValue(final Node node) {
			return getValue(node, true);
		}

		public static String getValue(final Node node, final boolean trimValue) {
			String value = null;
			final Node child = node.getFirstChild();
			if (child != null) {
				value = child.getNodeValue();
				if (value != null && trimValue) {
					value = value.trim();
				}
			}
			return value;
		}

		public static int getChildValueAsInt(final Node parentNode, final String childName) throws RrdException {
		    final String valueStr = getChildValue(parentNode, childName);
			return Integer.parseInt(valueStr);
		}

		public static int getValueAsInt(final Node node) {
			return Integer.parseInt(getValue(node));
		}

		public static long getChildValueAsLong(final Node parentNode, final String childName) throws RrdException {
			final String valueStr = getChildValue(parentNode, childName);
			return Long.parseLong(valueStr);
		}

		public static long getValueAsLong(final Node node) {
			return Long.parseLong(getValue(node));
		}

		public static double getChildValueAsDouble(final Node parentNode, final String childName) throws RrdException {
			return Util.parseDouble(getChildValue(parentNode, childName));
		}

		public static double getValueAsDouble(final Node node) {
			return Util.parseDouble(getValue(node));
		}

		public static boolean getChildValueAsBoolean(final Node parentNode, final String childName) throws RrdException {
			return Util.parseBoolean(getChildValue(parentNode, childName));
		}

		public static boolean getValueAsBoolean(final Node node) {
			return Util.parseBoolean(getValue(node));
		}

		public static Element getRootElement(final InputSource inputSource) throws RrdException, IOException {
		    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setValidating(false);
			factory.setNamespaceAware(false);
			try {
			    final DocumentBuilder builder = factory.newDocumentBuilder();
			    final Document doc = builder.parse(inputSource);
				return doc.getDocumentElement();
			}
			catch (final ParserConfigurationException e) {
				throw new RrdException(e);
			}
			catch (final SAXException e) {
				throw new RrdException(e);
			}
		}

		public static Element getRootElement(final String xmlString) throws RrdException, IOException {
			return getRootElement(new InputSource(new StringReader(xmlString)));
		}

		public static Element getRootElement(final File xmlFile) throws RrdException, IOException {
			Reader reader = null;
			try {
				reader = new FileReader(xmlFile);
				return getRootElement(new InputSource(reader));
			}
			finally {
				if (reader != null) {
					reader.close();
				}
			}
		}
	}

	private static long lastLap = System.currentTimeMillis();

	/**
	 * Function used for debugging purposes and performance bottlenecks detection.
	 * Probably of no use for end users of JRobin.
	 *
	 * @return String representing time in seconds since last
	 *         <code>getLapTime()</code> method call.
	 */
	public static String getLapTime() {
	    final long newLap = System.currentTimeMillis();
		final double seconds = (newLap - lastLap) / 1000.0;
		lastLap = newLap;
		return "[" + seconds + " sec]";
	}

	/**
	 * Returns the root directory of the JRobin distribution. Useful in some demo applications,
	 * probably of no use anywhere else.
	 * <p>
	 * The function assumes that all JRobin .class files are placed under
	 * the &lt;root&gt;/classes subdirectory and that all jars (libraries) are placed in the
	 * &lt;root&gt;/lib subdirectory (the original JRobin directory structure).
	 *
	 * @return absolute path to JRobin's home directory
	 */
	public static String getJRobinHomeDirectory() {
	    final String className = Util.class.getName().replace('.', '/');
		String uri = Util.class.getResource("/" + className + ".class").toString();
		//System.out.println(uri);
		if (uri.startsWith("file:/")) {
			uri = uri.substring(6);
			File file = new File(uri);
			// let's go 5 steps backwards
			for (int i = 0; i < 5; i++) {
				file = file.getParentFile();
			}
			uri = file.getAbsolutePath();
		}
		else if (uri.startsWith("jar:file:/")) {
			uri = uri.substring(9, uri.lastIndexOf('!'));
			File file = new File(uri);
			// let's go 2 steps backwards
			for (int i = 0; i < 2; i++) {
				file = file.getParentFile();
			}
			uri = file.getAbsolutePath();
		}
		else {
			uri = null;
		}
		return uri;
	}

	/**
	 * Compares two doubles but treats all NaNs as equal.
	 * In Java (by default) Double.NaN == Double.NaN always returns <code>false</code>
	 *
	 * @param x the first value
	 * @param y the second value
	 * @return <code>true</code> if x and y are both equal to Double.NaN, or if x == y. <code>false</code> otherwise
	 */
	public static boolean equal(final double x, final double y) {
		return (Double.isNaN(x) && Double.isNaN(y)) || (x == y);
	}

	/**
	 * Returns canonical file path for the given file path
	 *
	 * @param path Absolute or relative file path
	 * @return Canonical file path
	 * @throws IOException Thrown if canonical file path could not be resolved
	 */
	public static String getCanonicalPath(final String path) throws IOException {
		return new File(path).getCanonicalPath();
	}

	/**
	 * Returns last modification time for the given file.
	 *
	 * @param file File object representing file on the disk
	 * @return Last modification time in seconds (without milliseconds)
	 */
	public static long getLastModified(final String file) {
		return (new File(file).lastModified() + 500L) / 1000L;
	}

	/**
	 * Checks if the file with the given file name exists
	 *
	 * @param filename File name
	 * @return <code>true</code> if file exists, <code>false</code> otherwise
	 */
	public static boolean fileExists(final String filename) {
		return new File(filename).exists();
	}

	/**
	 * Finds max value for an array of doubles (NaNs are ignored). If all values in the array
	 * are NaNs, NaN is returned.
	 *
	 * @param values Array of double values
	 * @return max value in the array (NaNs are ignored)
	 */
	public static double max(final double[] values) {
		double max = Double.NaN;
		for (final double value : values) {
			max = Util.max(max, value);
		}
		return max;
	}

	/**
	 * Finds min value for an array of doubles (NaNs are ignored). If all values in the array
	 * are NaNs, NaN is returned.
	 *
	 * @param values Array of double values
	 * @return min value in the array (NaNs are ignored)
	 */
	public static double min(final double[] values) {
		double min = Double.NaN;
		for (final double value : values) {
			min = Util.min(min, value);
		}
		return min;
	}

	/**
	 * Equivalent of the C-style sprintf function. Sorry, it works only in Java5.
	 *
	 * @param format Format string
	 * @param args   Arbitrary list of arguments
	 * @return Formatted string
	 */
	public static String sprintf(final String format, final Object ... args) {
	    final String fmt = format.replaceAll("([^%]|^)%([^a-zA-Z%]*)l(f|g|e)", "$1%$2$3");
		return String.format(fmt, args);
	}
}
