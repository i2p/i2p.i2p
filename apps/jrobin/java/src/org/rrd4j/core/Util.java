package org.rrd4j.core;

import org.rrd4j.core.timespec.TimeParser;
import org.rrd4j.core.timespec.TimeSpec;
import org.rrd4j.ConsolFun;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.io.*;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Class defines various utility functions used in Rrd4j.
 *
 * @author Sasa Markovic
 */
public class Util {

    /** Constant <code>MAX_LONG=Long.MAX_VALUE</code> */
    public static final long MAX_LONG = Long.MAX_VALUE;
    /** Constant <code>MIN_LONG=-Long.MAX_VALUE</code> */
    public static final long MIN_LONG = -Long.MAX_VALUE;

    /** Constant <code>MAX_DOUBLE=Double.MAX_VALUE</code> */
    public static final double MAX_DOUBLE = Double.MAX_VALUE;
    /** Constant <code>MIN_DOUBLE=-Double.MAX_VALUE</code> */
    public static final double MIN_DOUBLE = -Double.MAX_VALUE;

    // pattern RRDTool uses to format doubles in XML files
    static final String PATTERN = "0.0000000000E00";
    // directory under $USER_HOME used for demo graphs storing
    static final String RRD4J_DIR = "rrd4j-demo";

    static final ThreadLocal<NumberFormat> df = new ThreadLocal<NumberFormat>() {
        @Override
        protected NumberFormat initialValue() {
            DecimalFormat ldf = (DecimalFormat) NumberFormat.getNumberInstance(Locale.ENGLISH);
            ldf.applyPattern(PATTERN);
            ldf.setPositivePrefix("+");
            return ldf;
        }
    };

    private static final Pattern SPRINTF_PATTERN = Pattern.compile("([^%]|^)%([^a-zA-Z%]*)l(f|g|e)");

    private Util() {

    }

    /**
     * Converts an array of long primitives to an array of doubles.
     *
     * @param array input array of long values.
     * @return Same array but with all values as double.
     */
    public static double[] toDoubleArray(final long[] array) {
        double[] values = new double[array.length];
        for (int i = 0; i < array.length; i++)
            values[i] = array[i];
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
     * @param step      Step in seconds
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
            return df.get().format(x);
        }
        return Double.toString(x);
    }

    static String formatDouble(double x, boolean forceExponents) {
        return formatDouble(x, Double.toString(Double.NaN), forceExponents);
    }

    /**
     * Formats double as a string using exponential notation (RRDTool like). Used for debugging
     * through the project.
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
    public static long getTimestamp(Date date) {
        // round to whole seconds, ignore milliseconds
        return (date.getTime() + 499L) / 1000L;
    }

    /**
     * Returns timestamp (unix epoch) for the given Calendar object
     *
     * @param gc Calendar object
     * @return Corresponding timestamp (without milliseconds)
     */
    public static long getTimestamp(Calendar gc) {
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
    public static long getTimestamp(int year, int month, int day, int hour, int min) {
        Calendar calendar = Calendar.getInstance();
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
     * <p>Parses at-style time specification and returns the corresponding timestamp. For example:</p>
     * <pre>
     * long t = Util.getTimestamp("now-1d");
     * </pre>
     *
     * @param atStyleTimeSpec at-style time specification. For the complete explanation of the syntax
     *                        allowed see RRDTool's <code>rrdfetch</code> man page.<p>
     * @return timestamp in seconds since epoch.
     */
    public static long getTimestamp(String atStyleTimeSpec) {
        TimeSpec timeSpec = new TimeParser(atStyleTimeSpec).parse();
        return timeSpec.getTimestamp();
    }

    /**
     * <p>Parses two related at-style time specifications and returns corresponding timestamps. For example:</p>
     * <pre>
     * long[] t = Util.getTimestamps("end-1d","now");
     * </pre>
     *
     * @param atStyleTimeSpec1 Starting at-style time specification. For the complete explanation of the syntax
     *                         allowed see RRDTool's <code>rrdfetch</code> man page.<p>
     * @param atStyleTimeSpec2 Ending at-style time specification. For the complete explanation of the syntax
     *                         allowed see RRDTool's <code>rrdfetch</code> man page.<p>
     * @return An array of two longs representing starting and ending timestamp in seconds since epoch.
     */
    public static long[] getTimestamps(String atStyleTimeSpec1, String atStyleTimeSpec2) {
        TimeSpec timeSpec1 = new TimeParser(atStyleTimeSpec1).parse();
        TimeSpec timeSpec2 = new TimeParser(atStyleTimeSpec2).parse();
        return TimeSpec.getTimestamps(timeSpec1, timeSpec2);
    }

    /**
     * Parses input string as a double value. If the value cannot be parsed, Double.NaN
     * is returned (NumberFormatException is never thrown).
     *
     * @param valueStr String representing double value
     * @return a double corresponding to the input string
     */
    public static double parseDouble(String valueStr) {
        double value;
        try {
            value = Double.parseDouble(valueStr);
        }
        catch (NumberFormatException nfe) {
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
    public static boolean isDouble(String s) {
        try {
            Double.parseDouble(s);
            return true;
        }
        catch (NumberFormatException nfe) {
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
    public static boolean parseBoolean(String valueStr) {
        return valueStr !=null && (valueStr.equalsIgnoreCase("true") ||
                valueStr.equalsIgnoreCase("on") ||
                valueStr.equalsIgnoreCase("yes") ||
                valueStr.equalsIgnoreCase("y") ||
                valueStr.equalsIgnoreCase("1"));
    }

    /**
     * Parses input string as color. The color string should be of the form #RRGGBB (no alpha specified,
     * opaque color) or #RRGGBBAA (alpa specified, transparent colors). Leading character '#' is
     * optional.
     *
     * @param valueStr Input string, for example #FFAA24, #AABBCC33, 010203 or ABC13E4F
     * @return Paint object
     * @throws java.lang.IllegalArgumentException If the input string is not 6 or 8 characters long (without optional '#')
     */
    public static Paint parseColor(String valueStr) {
        String c = valueStr.startsWith("#") ? valueStr.substring(1) : valueStr;
        if (c.length() != 6 && c.length() != 8) {
            throw new IllegalArgumentException("Invalid color specification: " + valueStr);
        }
        String r = c.substring(0, 2), g = c.substring(2, 4), b = c.substring(4, 6);
        if (c.length() == 6) {
            return new Color(Integer.parseInt(r, 16), Integer.parseInt(g, 16), Integer.parseInt(b, 16));
        }
        else {
            String a = c.substring(6);
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
     * Returns path to directory used for placement of Rrd4j demo graphs and creates it
     * if necessary.
     *
     * @return Path to demo directory (defaults to $HOME/rrd4j/) if directory exists or
     *         was successfully created. Null if such directory could not be created.
     */
    public static String getRrd4jDemoDirectory() {
        Path root;
        if (System.getProperty("rrd4j.demopath") != null) {
            root = Paths.get(System.getProperty("rrd4j.demopath"));
        } else {
            root = Paths.get(getUserHomeDirectory(), RRD4J_DIR);
        }
        try {
            Files.createDirectories(root);
            return root.toAbsolutePath().toString() + File.separator;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns full path to the file stored in the demo directory of Rrd4j
     *
     * @param filename Partial path to the file stored in the demo directory of Rrd4j
     *                 (just name and extension, without parent directories)
     * @return Full path to the file
     */
    public static String getRrd4jDemoPath(String filename) {
        String demoDir = getRrd4jDemoDirectory();
        if (demoDir != null) {
            return demoDir + filename;
        }
        else {
            return null;
        }
    }

    static boolean sameFilePath(String pathname1, String pathname2) throws IOException {
        Path path1 = Paths.get(pathname1);
        Path path2 = Paths.get(pathname2);
        if (Files.exists(path1) != Files.exists(path2)) {
            return false;
        } else if (Files.exists(path1) && Files.exists(path2)){
            path1 = Paths.get(pathname1).toRealPath().normalize();
            path2 = Paths.get(pathname2).toRealPath().normalize();
            return Files.isSameFile(path1, path2);
        } else {
            return false;
        }
    }

    static int getMatchingDatasourceIndex(RrdDb rrd1, int dsIndex, RrdDb rrd2) throws IOException {
        String dsName = rrd1.getDatasource(dsIndex).getName();
        try {
            return rrd2.getDsIndex(dsName);
        }
        catch (IllegalArgumentException e) {
            return -1;
        }
    }

    static int getMatchingArchiveIndex(RrdDb rrd1, int arcIndex, RrdDb rrd2)
            throws IOException {
        Archive archive = rrd1.getArchive(arcIndex);
        ConsolFun consolFun = archive.getConsolFun();
        int steps = archive.getSteps();
        try {
            return rrd2.getArcIndex(consolFun, steps);
        }
        catch (IllegalArgumentException e) {
            return -1;
        }
    }

    static String getTmpFilename() throws IOException {
        return File.createTempFile("rrd4j_", ".tmp").getCanonicalPath();
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
    public static Calendar getCalendar(String timeStr) {
        // try to parse it as long
        try {
            long timestamp = Long.parseLong(timeStr);
            return Util.getCalendar(timestamp);
        }
        catch (NumberFormatException e) {
        }
        // not a long timestamp, try to parse it as data
        SimpleDateFormat df = new SimpleDateFormat(ISO_DATE_FORMAT);
        df.setLenient(false);
        try {
            Date date = df.parse(timeStr);
            return Util.getCalendar(date);
        }
        catch (ParseException e) {
            throw new IllegalArgumentException("Time/date not in " + ISO_DATE_FORMAT +
                    " format: " + timeStr);
        }
    }

    /**
     * Various DOM utility functions.
     */
    public static class Xml {

        private static class SingletonHelper {
            private static final DocumentBuilderFactory factory;
            static {
                factory = DocumentBuilderFactory.newInstance();
                try {
                    factory.setIgnoringComments(true);
                    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    factory.setValidating(false);
                    factory.setNamespaceAware(false);
                } catch (ParserConfigurationException e) {
                    throw new UnsupportedOperationException("Missing DOM feature: " + e.getMessage(), e);
                }
            }
        }

        private static final ErrorHandler eh = new ErrorHandler() {
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
            public void warning(SAXParseException exception) throws SAXException {
                throw exception;
            }
        };

        private Xml() {

        }

        public static Node[] getChildNodes(Node parentNode) {
            return getChildNodes(parentNode, null);
        }

        public static Node[] getChildNodes(Node parentNode, String childName) {
            ArrayList<Node> nodes = new ArrayList<>();
            NodeList nodeList = parentNode.getChildNodes();
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE && (childName == null || node.getNodeName().equals(childName))) {
                    nodes.add(node);
                }
            }
            return nodes.toArray(new Node[0]);
        }

        public static Node getFirstChildNode(Node parentNode, String childName) {
            Node[] childs = getChildNodes(parentNode, childName);
            if (childs.length > 0) {
                return childs[0];
            }
            throw new IllegalArgumentException("XML Error, no such child: " + childName);
        }

        public static boolean hasChildNode(Node parentNode, String childName) {
            Node[] childs = getChildNodes(parentNode, childName);
            return childs.length > 0;
        }

        // -- Wrapper around getChildValue with trim
        public static String getChildValue(Node parentNode, String childName) {
            return getChildValue(parentNode, childName, true);
        }

        public static String getChildValue(Node parentNode, String childName, boolean trim) {
            NodeList children = parentNode.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeName().equals(childName)) {
                    return getValue(child, trim);
                }
            }
            throw new IllegalStateException("XML Error, no such child: " + childName);
        }

        // -- Wrapper around getValue with trim
        public static String getValue(Node node) {
            return getValue(node, true);
        }

        public static String getValue(Node node, boolean trimValue) {
            String value = null;
            Node child = node.getFirstChild();
            if (child != null) {
                value = child.getNodeValue();
                if (value != null && trimValue) {
                    value = value.trim();
                }
            }
            return value;
        }

        public static int getChildValueAsInt(Node parentNode, String childName) {
            String valueStr = getChildValue(parentNode, childName);
            return Integer.parseInt(valueStr);
        }

        public static int getValueAsInt(Node node) {
            String valueStr = getValue(node);
            return Integer.parseInt(valueStr);
        }

        public static long getChildValueAsLong(Node parentNode, String childName) {
            String valueStr = getChildValue(parentNode, childName);
            return Long.parseLong(valueStr);
        }

        public static long getValueAsLong(Node node) {
            String valueStr = getValue(node);
            return Long.parseLong(valueStr);
        }

        public static double getChildValueAsDouble(Node parentNode, String childName) {
            String valueStr = getChildValue(parentNode, childName);
            return Util.parseDouble(valueStr);
        }

        public static double getValueAsDouble(Node node) {
            String valueStr = getValue(node);
            return Util.parseDouble(valueStr);
        }

        public static boolean getChildValueAsBoolean(Node parentNode, String childName) {
            String valueStr = getChildValue(parentNode, childName);
            return Util.parseBoolean(valueStr);
        }

        public static boolean getValueAsBoolean(Node node) {
            String valueStr = getValue(node);
            return Util.parseBoolean(valueStr);
        }

        public static Element getRootElement(InputSource inputSource) throws IOException {
            try {
                DocumentBuilder builder = SingletonHelper.factory.newDocumentBuilder();
                builder.setErrorHandler(eh);
                Document doc = builder.parse(inputSource);
                return doc.getDocumentElement();
            }
            catch (ParserConfigurationException | SAXException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        public static Element getRootElement(String xmlString) throws IOException {
            return getRootElement(new InputSource(new StringReader(xmlString)));
        }

        public static Element getRootElement(File xmlFile) throws IOException {
            try (Reader reader = new FileReader(xmlFile)) {
                return getRootElement(new InputSource(reader));
            }
        }
    }

    private static long lastLap = System.currentTimeMillis();

    /**
     * Function used for debugging purposes and performance bottlenecks detection.
     * Probably of no use for end users of Rrd4j.
     *
     * @return String representing time in seconds since last
     *         <code>getLapTime()</code> method call.
     */
    public static String getLapTime() {
        long newLap = System.currentTimeMillis();
        double seconds = (newLap - lastLap) / 1000.0;
        lastLap = newLap;
        return "[" + seconds + " sec]";
    }

    /**
     * <p>Returns the root directory of the Rrd4j distribution. Useful in some demo applications,
     * probably of no use anywhere else.</p>
     * <p>The function assumes that all Rrd4j .class files are placed under
     * the &lt;root&gt;/classes subdirectory and that all jars (libraries) are placed in the
     * &lt;root&gt;/lib subdirectory (the original Rrd4j directory structure).</p>
     *
     * @return absolute path to Rrd4j's home directory
     */
    public static String getRrd4jHomeDirectory() {
        String homedir = null;
        try {
            String className = Util.class.getName().replace('.', '/');
            URI uri = Util.class.getResource("/" + className + ".class").toURI();
            if ("file".equals(uri.getScheme())) {
                homedir = Paths.get(uri).toString();
            }
            else if ("jar".equals(uri.getScheme())) {
                // JarURLConnection doesn't open the JAR
                JarURLConnection connection = (JarURLConnection) uri.toURL().openConnection();
                homedir = connection.getJarFileURL().getFile();
            }
        } catch (URISyntaxException | IOException e) {
        }
        if (homedir != null) {
            return Paths.get(homedir).toAbsolutePath().toString();
        } else {
            return null;
        }
    }

    /**
     * Compares two doubles but treats all NaNs as equal.
     * In Java (by default) Double.NaN == Double.NaN always returns <code>false</code>
     *
     * @param x the first value
     * @param y the second value
     * @return <code>true</code> if x and y are both equal to Double.NaN, or if x == y. <code>false</code> otherwise
     */
    public static boolean equal(double x, double y) {
        return (Double.isNaN(x) && Double.isNaN(y)) || (x == y);
    }

    /**
     * Returns canonical file path for the given file path
     *
     * @param path Absolute or relative file path
     * @return Canonical file path
     * @throws java.io.IOException Thrown if canonical file path could not be resolved
     */
    public static String getCanonicalPath(String path) throws IOException {
        return new File(path).getCanonicalPath();
    }

    /**
     * Returns last modification time for the given file.
     *
     * @param file File object representing file on the disk
     * @return Last modification time in seconds (without milliseconds)
     */
    public static long getLastModified(String file) {
        return (new File(file).lastModified() + 500L) / 1000L;
    }

    /**
     * Checks if the file with the given file name exists
     *
     * @param filename File name
     * @return <code>true</code> if file exists, <code>false</code> otherwise
     */
    public static boolean fileExists(String filename) {
        return new File(filename).exists();
    }

    /**
     * Finds max value for an array of doubles (NaNs are ignored). If all values in the array
     * are NaNs, NaN is returned.
     *
     * @param values Array of double values
     * @return max value in the array (NaNs are ignored)
     */
    public static double max(double[] values) {
        double max = Double.NaN;
        for (double value : values) {
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
    public static double min(double[] values) {
        double min = Double.NaN;
        for (double value : values) {
            min = Util.min(min, value);
        }
        return min;
    }

    /**
     * Equivalent of the C-style sprintf function.
     *
     * @param format Format string
     * @param args   Arbitrary list of arguments
     * @return Formatted string
     * @param l a {@link java.util.Locale} object.
     */
    public static String sprintf(Locale l, String format, Object... args) {
        String fmt =  SPRINTF_PATTERN.matcher(format).replaceAll("$1%$2$3");
        return String.format(l, fmt, args);
    }
}
