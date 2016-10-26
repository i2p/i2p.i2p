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
package org.jrobin.graph;

import org.jrobin.core.RrdException;
import org.jrobin.core.Util;
import org.jrobin.data.Plottable;

import java.awt.*;
import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Class which should be used to define new JRobin graph. Once constructed and populated with data
 * object of this class should be passed to the constructor of the {@link RrdGraph} class which
 * will actually create the graph.
 * <p>
 * The text printed below the actual graph can be formated by appending
 * special escaped characters at the end of a text. When ever such a
 * character occurs, all pending text is pushed onto the graph according to
 * the character specified.
 * <p>
 * Valid markers are: \j for justified, \l for left aligned, \r for right
 * aligned and \c for centered.
 * <p>
 * Normally there are two space characters inserted between every two
 * items printed into the graph. The space following a string can be
 * suppressed by putting a \g at the end of the string. The \g also squashes
 * any space inside the string if it is at the very end of the string.
 * This can be used in connection with %s to suppress empty unit strings.
 * <p>
 * A special case is COMMENT:\s this inserts some additional vertical
 * space before placing the next row of legends.
 * <p>
 * When text has to be formated without special instructions from your
 * side, RRDTool will automatically justify the text as soon as one string
 * goes over the right edge. If you want to prevent the justification
 * without forcing a newline, you can use the special tag \J at the end of
 * the string to disable the auto justification.
 */
public class RrdGraphDef implements RrdGraphConstants {
    boolean poolUsed = false; // ok
    boolean antiAliasing = false; // ok
    String filename = RrdGraphConstants.IN_MEMORY_IMAGE; // ok
    long startTime, endTime; // ok
    TimeAxisSetting timeAxisSetting = null; // ok
    ValueAxisSetting valueAxisSetting = null; // ok
    boolean altYGrid = false; // ok
    boolean noMinorGrid = false; // ok
    boolean altYMrtg = false; // ok
    boolean altAutoscale = false; // ok
    boolean altAutoscaleMax = false; // ok
    int unitsExponent = Integer.MAX_VALUE; // ok
    int unitsLength = DEFAULT_UNITS_LENGTH; // ok
    String verticalLabel = null; // ok
    int width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT; // ok
    boolean interlaced = false; // ok
    String imageInfo = null; // ok
    String imageFormat = DEFAULT_IMAGE_FORMAT; // ok
    float imageQuality = DEFAULT_IMAGE_QUALITY; // ok
    String backgroundImage = null; // ok
    String overlayImage = null; // ok
    String unit = null; // ok
    String signature = "Created with JRobin"; // ok
    boolean lazy = false; // ok
    double minValue = Double.NaN; // ok
    double maxValue = Double.NaN; // ok
    boolean rigid = false; // ok
    double base = DEFAULT_BASE;  // ok
    boolean logarithmic = false; // ok
    Paint[] colors = new Paint[]{
            // ok
            DEFAULT_CANVAS_COLOR,
            DEFAULT_BACK_COLOR,
            DEFAULT_SHADEA_COLOR,
            DEFAULT_SHADEB_COLOR,
            DEFAULT_GRID_COLOR,
            DEFAULT_MGRID_COLOR,
            DEFAULT_FONT_COLOR,
            DEFAULT_FRAME_COLOR,
            DEFAULT_ARROW_COLOR
    };
    boolean noLegend = false; // ok
    boolean onlyGraph = false; // ok
    boolean forceRulesLegend = false; // ok
    String title = null; // ok
    long step = 0; // ok
    Font[] fonts = new Font[FONTTAG_NAMES.length];
    boolean drawXGrid = true; // ok
    boolean drawYGrid = true; // ok
    int firstDayOfWeek = FIRST_DAY_OF_WEEK; // ok
    boolean showSignature = true;
    File fontDir = null;

    List<Source> sources = new ArrayList<Source>();
    List<CommentText> comments = new ArrayList<CommentText>();
    List<PlotElement> plotElements = new ArrayList<PlotElement>();

    /**
     * Creates RrdGraphDef object and sets default time span (default ending time is 'now',
     * default starting time is 'end-1day'.
     */
    public RrdGraphDef() {
        try {
            setTimeSpan(Util.getTimestamps(DEFAULT_START, DEFAULT_END));
        } catch (RrdException e) {
            throw new RuntimeException(e);
        }

        String fontdirProperty = System.getProperty("jrobin.fontdir");
        if (fontdirProperty != null && fontdirProperty.length() != 0) {
            fontDir = new File(fontdirProperty);
        }

        fonts[FONTTAG_DEFAULT]   = new Font(DEFAULT_FONT_NAME, Font.PLAIN, 8);
        fonts[FONTTAG_TITLE]     = new Font(DEFAULT_FONT_NAME, Font.PLAIN, 9);
        fonts[FONTTAG_AXIS]      = new Font(DEFAULT_FONT_NAME, Font.PLAIN, 7);
        fonts[FONTTAG_UNIT]      = new Font(DEFAULT_FONT_NAME, Font.PLAIN, 8);
        fonts[FONTTAG_LEGEND]    = new Font(DEFAULT_FONT_NAME, Font.PLAIN, 8);
        fonts[FONTTAG_WATERMARK] = new Font(DEFAULT_FONT_NAME, Font.PLAIN, 1).deriveFont(5.5F);
    }

    protected Font getFontFromResourceName(String name) {
        Font font = null;
        Exception exception = null;
        URL file = null;

        if (fontDir != null) {
            try {
                file = new URL("file://" + new File(fontDir, name).getAbsolutePath());
            } catch (MalformedURLException e) {
                // fall through to the jar
                exception = e;
            }
        }
        if (file == null) {
            file = this.getClass().getResource(name);
        }

        if (file != null) {
            // System.err.println("Found a font URL: " + file.toExternalForm());
            try {
                InputStream fontStream = file.openStream();
                font = Font.createFont(Font.TRUETYPE_FONT, fontStream);
                fontStream.close();
            } catch (Exception e) {
                exception = e;
            }
        }
        else {
            // we can't find our fonts, fall back to the system font
            System.err.println("An error occurred loading the font '" + name + "'.  Falling back to the default.");
            if (exception != null) {
                System.err.println(exception.getLocalizedMessage());
            }
            font = new Font(DEFAULT_FONT_NAME, Font.PLAIN, 10);
        }

        if (font == null) {
            font = new Font(null, Font.PLAIN, 10);
        }
        return font;
    }

    /**
     * Sets the signature string that runs along the right-side of the graph.
     * Defaults to "Created with JRobin".
     *
     * @param signature the string to print
     */
    public void setSignature(String signature) {
        this.signature = signature;
    }

    /**
     * Gets the signature string that runs along the right-side of the graph.
     *
     * @return the signature string
     */
    public String getSignature() {
        return this.signature;
    }

    /**
     * Sets the time when the graph should begin. Time in seconds since epoch
     * (1970-01-01) is required. Negative numbers are relative to the current time.
     *
     * @param time Starting time for the graph in seconds since epoch
     */
    public void setStartTime(long time) {
        this.startTime = time;
        if (time <= 0) {
            this.startTime += Util.getTime();
        }
    }

    /**
     * Sets the time when the graph should end. Time in seconds since epoch
     * (1970-01-01) is required. Negative numbers are relative to the current time.
     *
     * @param time Ending time for the graph in seconds since epoch
     */
    public void setEndTime(long time) {
        this.endTime = time;
        if (time <= 0) {
            this.endTime += Util.getTime();
        }
    }

    /**
     * Sets starting and ending time for the for the graph. Timestamps in seconds since epoch are
     * required. Negative numbers are relative to the current time.
     *
     * @param startTime Starting time in seconds since epoch
     * @param endTime   Ending time in seconds since epoch
     */
    public void setTimeSpan(long startTime, long endTime) {
        setStartTime(startTime);
        setEndTime(endTime);
    }

    /**
     * Sets starting and ending time for the for the graph. Timestamps in seconds since epoch are
     * required.
     *
     * @param timestamps Array of timestamps. The first array item will be chosen for the starting
     *                   timestamp. The last array item will be chosen for the ending timestamp.
     */
    public void setTimeSpan(long[] timestamps) {
        setTimeSpan(timestamps[0], timestamps[timestamps.length - 1]);
    }

    /**
     * Sets RrdDbPool usage policy (defaults to true). If set to true,
     * {@link org.jrobin.core.RrdDbPool RrdDbPool} will be used to
     * access individual RRD files. If set to false, RRD files will be accessed directly.
     *
     * @param poolUsed true, if RrdDbPool class should be used. False otherwise.
     */
    public void setPoolUsed(boolean poolUsed) {
        this.poolUsed = poolUsed;
    }

    /**
     * Sets the name of the graph to generate. Since JRobin outputs GIFs, PNGs,
     * and JPEGs it's recommended that the filename end in either .gif,
     * .png or .jpg. JRobin does not enforce this, however. If the filename is
     * set to '-' the image will be created only in memory (no file will be created).
     * PNG and GIF formats are recommended but JPEGs should be avoided.
     *
     * @param filename Path to the image file
     */
    public void setFilename(String filename) {
        this.filename = filename;
    }

    /**
     * Configures x-axis grid and labels. The x-axis label is quite complex to configure.
     * So if you don't have very special needs, you can rely on the autoconfiguration to
     * get this right.
     * <p>
     * Otherwise, you have to configure three elements making up the x-axis labels
     * and grid. The base grid, the major grid and the labels.
     * The configuration is based on the idea that you first specify a well
     * known amount of time and then say how many times
     * it has to pass between each minor/major grid line or label. For the label
     * you have to define two additional items: The precision of the label
     * in seconds and the format used to generate the text
     * of the label.
     * <p>
     * For example, if you wanted a graph with a base grid every 10 minutes and a major
     * one every hour, with labels every hour you would use the following
     * x-axis definition.
     * <p>
     * <pre>
     * setTimeAxis(RrdGraphConstants.MINUTE, 10,
     *             RrdGraphConstants.HOUR, 1,
     *             RrdGraphConstants.HOUR, 1,
     *             0, "%H:%M")
     * </pre>
     * <p>
     * The precision in this example is 0 because the %X format is exact.
     * If the label was the name of the day, we would have had a precision
     * of 24 hours, because when you say something like 'Monday' you mean
     * the whole day and not Monday morning 00:00. Thus the label should
     * be positioned at noon. By defining a precision of 24 hours or
     * rather 86400 seconds, you make sure that this happens.
     *
     * @param minorUnit        Minor grid unit. Minor grid, major grid and label units
     *                         can be one of the following constants defined in
     *                         {@link RrdGraphConstants}: {@link RrdGraphConstants#SECOND SECOND},
     *                         {@link RrdGraphConstants#MINUTE MINUTE}, {@link RrdGraphConstants#HOUR HOUR},
     *                         {@link RrdGraphConstants#DAY DAY}, {@link RrdGraphConstants#WEEK WEEK},
     *                         {@link RrdGraphConstants#MONTH MONTH}, {@link RrdGraphConstants#YEAR YEAR}.
     * @param minorUnitCount   Number of minor grid units between minor grid lines.
     * @param majorUnit        Major grid unit.
     * @param majorUnitCount   Number of major grid units between major grid lines.
     * @param labelUnit        Label unit.
     * @param labelUnitCount   Number of label units between labels.
     * @param labelSpan        Label precision
     * @param simpleDateFormat Date format (SimpleDateFormat pattern of strftime-like pattern)
     */
    public void setTimeAxis(int minorUnit, int minorUnitCount, int majorUnit, int majorUnitCount,
                            int labelUnit, int labelUnitCount, int labelSpan, String simpleDateFormat) {
        timeAxisSetting = new TimeAxisSetting(minorUnit, minorUnitCount, majorUnit, majorUnitCount,
                labelUnit, labelUnitCount, labelSpan, simpleDateFormat);
    }

    /**
     * Sets vertical axis grid and labels. Makes vertical grid lines appear
     * at gridStep interval. Every labelFactor*gridStep, a major grid line is printed,
     * along with label showing the value of the grid line.
     *
     * @param gridStep    Minor grid step
     * @param labelFactor Specifies how many minor minor grid steps will appear between labels
     *                    (major grid lines)
     */
    public void setValueAxis(double gridStep, int labelFactor) {
        valueAxisSetting = new ValueAxisSetting(gridStep, labelFactor);
    }

    /**
     * Places Y grid dynamically based on graph Y range. Algorithm ensures
     * that you always have grid, that there are enough but not too many
     * grid lines and the grid is metric. That is grid lines are placed
     * every 1, 2, 5 or 10 units.
     *
     * @param altYGrid true, if Y grid should be calculated dynamically (defaults to false)
     */
    public void setAltYGrid(boolean altYGrid) {
        this.altYGrid = altYGrid;
    }

    /**
     * Use this method to turn off minor grid lines (printed by default)
     *
     * @param noMinorGrid true, to turn off, false to turn on (default)
     */
    public void setNoMinorGrid(boolean noMinorGrid) {
        this.noMinorGrid = noMinorGrid;
    }

    /**
     * Use this method to request MRTG-like graph (false by default)
     *
     * @param altYMrtg true, to create MRTG-like graph, false otherwise (default)
     */
    public void setAltYMrtg(boolean altYMrtg) {
        this.altYMrtg = altYMrtg;
    }

    /**
     * Computes Y range based on function absolute minimum and maximum
     * values. Default algorithm uses predefined set of ranges.  This is
     * good in many cases but it fails miserably when you need to graph
     * something like 260 + 0.001 * sin(x). Default algorithm will use Y
     * range from 250 to 300 and on the graph you will see almost straight
     * line. With --alt-autoscale Y range will be from slightly less the
     * 260 - 0.001 to slightly more then 260 + 0.001 and periodic behavior
     * will be seen.
     *
     * @param altAutoscale true to request alternative autoscaling, false otherwise
     *                     (default).
     */
    public void setAltAutoscale(boolean altAutoscale) {
        this.altAutoscale = altAutoscale;
    }

    /**
     * Computes Y range based on function absolute minimum and maximum
     * values. Where setAltAutoscale(true) will modify both the absolute maximum AND
     * minimum values, this option will only affect the maximum value. The
     * minimum value, if not defined elsewhere, will be 0. This
     * option can be useful when graphing router traffic when the WAN line
     * uses compression, and thus the throughput may be higher than the
     * WAN line speed.
     *
     * @param altAutoscaleMax true to request alternative autoscaling, false
     *                        otherwise (default)
     */
    public void setAltAutoscaleMax(boolean altAutoscaleMax) {
        this.altAutoscaleMax = altAutoscaleMax;
    }

    /**
     * Sets the 10**unitsExponent scaling of the y-axis values. Normally
     * values will be scaled to the appropriate units (k, M, etc.). However
     * you may wish to display units always in k (Kilo, 10e3) even if
     * the data is in the M (Mega, 10e6) range for instance.  Value should
     * be an integer which is a multiple of 3 between -18 and 18, inclu-
     * sive. It is the exponent on the units you which to use.  For example,
     * use 3 to display the y-axis values in k (Kilo, 10e3, thou-
     * sands), use -6 to display the y-axis values in u (Micro, 10e-6,
     * millionths). Use a value of 0 to prevent any scaling of the y-axis
     * values.
     *
     * @param unitsExponent the 10**unitsExponent value for scaling y-axis values.
     */
    public void setUnitsExponent(int unitsExponent) {
        this.unitsExponent = unitsExponent;
    }

    /**
     * Sets the character width on the left side of the graph for
     * y-axis values.
     *
     * @param unitsLength Number of characters on the left side of the graphs
     *                    reserved for vertical axis labels.
     */
    public void setUnitsLength(int unitsLength) {
        this.unitsLength = unitsLength;
    }

    /**
     * Sets vertical label on the left side of the graph. This is normally used
     * to specify the units used.
     *
     * @param verticalLabel Vertical axis label
     */
    public void setVerticalLabel(String verticalLabel) {
        this.verticalLabel = verticalLabel;
    }

    /**
     * Sets width of the drawing area within the graph. This affects the total
     * size of the image.
     *
     * @param width Width of the drawing area.
     */
    public void setWidth(int width) {
        this.width = width;
    }

    /**
     * Sets height of the drawing area within the graph. This affects the total
     * size of the image.
     *
     * @param height Height of the drawing area.
     */
    public void setHeight(int height) {
        this.height = height;
    }

    /**
     * Creates interlaced GIF image (currently not supported,
     * method is present only for RRDTool comaptibility).
     *
     * @param interlaced true, if GIF image should be interlaced.
     */
    public void setInterlaced(boolean interlaced) {
        this.interlaced = interlaced;
    }

    /**
     * Creates additional image information.
     * After the image has been created, the graph function uses imageInfo
     * format string (printf-like) to create output similar to
     * the {@link #print(String, String, String)} function.
     * The format string is supplied with the following parameters:
     * filename, xsize and ysize (in that particular order).
     * <p>
     * For example, in order to generate an IMG tag
     * suitable for including the graph into a web page, the command
     * would look like this:
     * <pre>
     * setImageInfo(&quot;&lt;IMG SRC='/img/%s' WIDTH='%d' HEIGHT='%d' ALT='Demo'&gt;&quot;);
     * </pre>
     *
     * @param imageInfo Image info format. Use %s placeholder for filename, %d placeholder for
     *                  image width and height.
     */
    public void setImageInfo(String imageInfo) {
        this.imageInfo = imageInfo;
    }

    /**
     * Sets image format.
     *
     * @param imageFormat "PNG", "GIF" or "JPG".
     */
    public void setImageFormat(String imageFormat) {
        this.imageFormat = imageFormat;
    }

    /**
     * Sets background image - currently, only PNG images can be used as background.
     *
     * @param backgroundImage Path to background image
     */
    public void setBackgroundImage(String backgroundImage) {
        this.backgroundImage = backgroundImage;
    }

    /**
     * Sets overlay image - currently, only PNG images can be used as overlay. Overlay image is
     * printed on the top of the image, once it is completely created.
     *
     * @param overlayImage Path to overlay image
     */
    public void setOverlayImage(String overlayImage) {
        this.overlayImage = overlayImage;
    }

    /**
     * Sets unit to be displayed on y axis. It is wise to use only short units on graph, however.
     *
     * @param unit Unit description
     */
    public void setUnit(String unit) {
        this.unit = unit;
    }

    /**
     * Creates graph only if the current graph is out of date or not existent.
     *
     * @param lazy true, if graph should be 'lazy', false otherwise (defualt)
     */
    public void setLazy(boolean lazy) {
        this.lazy = lazy;
    }

    /**
     * Sets the lower limit of a graph. But rather, this is the
     * maximum lower bound of a graph. For example, the value -100 will
     * result in a graph that has a lower limit of -100 or less.  Use this
     * method to expand graphs down.
     *
     * @param minValue Minimal value displayed on the graph
     */
    public void setMinValue(double minValue) {
        this.minValue = minValue;
    }

    /**
     * Defines the value normally located at the upper border of the
     * graph. If the graph contains higher values, the upper border will
     * move upwards to accommodate these values as well.
     * <p>
     * If you want to define an upper-limit which will not move in any
     * event you have to use {@link #setRigid(boolean)} method as well.
     *
     * @param maxValue Maximal value displayed on the graph.
     */
    public void setMaxValue(double maxValue) {
        this.maxValue = maxValue;
    }

    /**
     * Sets rigid boundaries mode. Normally JRObin will automatically expand
     * the lower and upper limit if the graph contains a value outside the
     * valid range. With the <code>true</code> argument you can disable this behavior.
     *
     * @param rigid true if uper and lower limits should not be expanded to accomodate
     *              values outside of the specified range. False otherwise (default).
     */
    public void setRigid(boolean rigid) {
        this.rigid = rigid;
    }

    /**
     * Sets default base for magnitude scaling. If you are graphing memory
     * (and NOT network traffic) this switch should be set to 1024 so that 1Kb is 1024 byte.
     * For traffic measurement, 1 kb/s is 1000 b/s.
     *
     * @param base Base value (defaults to 1000.0)
     */
    public void setBase(double base) {
        this.base = base;
    }

    /**
     * Sets logarithmic y-axis scaling.
     *
     * @param logarithmic true, for logarithmic scaling, false otherwise (default).
     */
    public void setLogarithmic(boolean logarithmic) {
        this.logarithmic = logarithmic;
    }

    /**
     * Overrides the colors for the standard elements of the graph. The colorTag
     * must be one of the following constants defined in the
     * {@link RrdGraphConstants}:
     * {@link RrdGraphConstants#COLOR_BACK COLOR_BACK} background,
     * {@link RrdGraphConstants#COLOR_CANVAS COLOR_CANVAS} canvas,
     * {@link RrdGraphConstants#COLOR_SHADEA COLOR_SHADEA} left/top border,
     * {@link RrdGraphConstants#COLOR_SHADEB COLOR_SHADEB} right/bottom border,
     * {@link RrdGraphConstants#COLOR_GRID COLOR_GRID} major grid,
     * {@link RrdGraphConstants#COLOR_MGRID COLOR_MGRID} minor grid,
     * {@link RrdGraphConstants#COLOR_FONT COLOR_FONT} font,
     * {@link RrdGraphConstants#COLOR_FRAME COLOR_FRAME} axis of the graph,
     * {@link RrdGraphConstants#COLOR_ARROW COLOR_ARROW} arrow. This method can
     * be called multiple times to set several colors.
     *
     * @param colorTag Color tag, as explained above.
     * @param color    Any color (paint) you like
     * @throws RrdException Thrown if invalid colorTag is supplied.
     */
    public void setColor(int colorTag, Paint color) throws RrdException {
        if (colorTag >= 0 && colorTag < colors.length) {
            colors[colorTag] = color;
        }
        else {
            throw new RrdException("Invalid color index specified: " + colorTag);
        }
    }

    /**
     * Overrides the colors for the standard elements of the graph by element name.
     * See {@link #setColor(int, java.awt.Paint)} for full explanation.
     *
     * @param colorName One of the following strings: "BACK", "CANVAS", "SHADEA", "SHADEB",
     *                  "GRID", "MGRID", "FONT", "FRAME", "ARROW"
     * @param color     Any color (paint) you like
     * @throws RrdException Thrown if invalid element name is supplied.
     */
    public void setColor(String colorName, Paint color) throws RrdException {
        setColor(getColorTagByName(colorName), color);
    }

    private static int getColorTagByName(String colorName) throws RrdException {
        for (int i = 0; i < COLOR_NAMES.length; i++) {
            if (COLOR_NAMES[i].equalsIgnoreCase(colorName)) {
                return i;
            }
        }
        throw new RrdException("Unknown color name specified: " + colorName);
    }

    /**
     * Suppress generation of legend, only render the graph.
     *
     * @param noLegend true if graph legend should be omitted. False otherwise (default).
     */
    public void setNoLegend(boolean noLegend) {
        this.noLegend = noLegend;
    }

    /**
     * Suppresses anything but the graph, works only for height &lt; 64.
     *
     * @param onlyGraph true if only graph should be created, false otherwise (default).
     */
    public void setOnlyGraph(boolean onlyGraph) {
        this.onlyGraph = onlyGraph;
    }

    /**
     * Force the generation of HRULE and VRULE legend even if those HRULE
     * or VRULE will not be drawn because out of graph boundaries.
     *
     * @param forceRulesLegend true if rule legend should be always printed,
     *                         false otherwise (default).
     */
    public void setForceRulesLegend(boolean forceRulesLegend) {
        this.forceRulesLegend = forceRulesLegend;
    }

    /**
     * Defines a title to be written into the graph.
     *
     * @param title Graph title.
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * Suggests which time step should be used by JRobin while processing data from RRD files.
     *
     * @param step Desired time step (don't use this method if you don't know what you're doing).
     */
    public void setStep(long step) {
        this.step = step;
    }

    /**
     * Get the default small font for graphing.
     *
     * @return the font
     */
    public Font getSmallFont() {
        return this.fonts[FONTTAG_DEFAULT];
    }

    /**
     * Get the default large font for graphing.
     *
     * @return the font
     */
    public Font getLargeFont() {
        return this.fonts[FONTTAG_TITLE];
    }

    /**
     * Sets default font for graphing. Note that JRobin will behave unpredictably if proportional
     * font is selected.
     *
     * @param smallFont Default font for graphing. Use only monospaced fonts.
     * @throws RrdException Thrown if invalid fontTag is supplied.
     */
    public void setSmallFont(final Font smallFont) throws RrdException{
        this.setFont(FONTTAG_DEFAULT, smallFont);
    }

    /**
     * Sets title font.
     *
     * @param largeFont Font to be used for graph title.
     * @throws RrdException Thrown if invalid fontTag is supplied.
     */
    public void setLargeFont(final Font largeFont) throws RrdException {
        this.setFont(FONTTAG_TITLE, largeFont);
    }

    /**
     * Sets font to be used for a specific font tag. The fontTag
     * must be one of the following constants defined in the
     * {@link RrdGraphConstants}:
     * {@link RrdGraphConstants#FONTTAG_DEFAULT FONTTAG_DEFAULT} default font,,
     * {@link RrdGraphConstants#FONTTAG_TITLE FONTTAG_TITLE} title,
     * {@link RrdGraphConstants#FONTTAG_AXIS FONTTAG_AXIS} grid axis,,
     * {@link RrdGraphConstants#FONTTAG_UNIT FONTTAG_UNIT} vertical unit label,,
     * {@link RrdGraphConstants#FONTTAG_LEGEND FONTTAG_LEGEND} legend,
     * {@link RrdGraphConstants#FONTTAG_WATERMARK FONTTAG_WATERMARK} watermark.
     * This method can be called multiple times to set several fonts.
     *
     * @param fontTag Font tag, as explained above.
     * @param font Font to be used for tag
     * @throws RrdException Thrown if invalid fontTag is supplied.
     */
    public void setFont(final int fontTag, final Font font) throws RrdException {
        this.setFont(fontTag, font, false);
    }

    /**
     * Sets font.
     *
     * @param fontTag Font tag, as explained above.
     * @param font Font to be used for tag
     * @param setAll Boolean to flag whether to set all fonts if fontTag == FONTTAG_DEFAULT
     * @throws RrdException Thrown if invalid fontTag is supplied.
     */
    public void setFont(final int fontTag, final Font font, final boolean setAll) throws RrdException {
        this.setFont(fontTag, font, setAll, false);
    }

    /**
     * Sets font.
     *
     * @param fontTag Font tag, as explained above.
     * @param font Font to be used for tag
     * @param setAll Boolean to flag whether to set all fonts if fontTag == FONTTAG_DEFAULT
     * @param keepSizes Boolean to flag whether to keep original font sizes if setting all fonts.
     */
    public void setFont(final int fontTag, final Font font, final boolean setAll, final boolean keepSizes) {
        if (fontTag == FONTTAG_DEFAULT && setAll) {
            if (keepSizes) {
               this.fonts[FONTTAG_DEFAULT] = font.deriveFont(this.fonts[FONTTAG_DEFAULT].getSize());
               this.fonts[FONTTAG_TITLE] = font.deriveFont(this.fonts[FONTTAG_TITLE].getSize());
               this.fonts[FONTTAG_AXIS] = font.deriveFont(this.fonts[FONTTAG_AXIS].getSize());
               this.fonts[FONTTAG_UNIT] = font.deriveFont(this.fonts[FONTTAG_UNIT].getSize());
               this.fonts[FONTTAG_LEGEND] = font.deriveFont(this.fonts[FONTTAG_LEGEND].getSize());
               this.fonts[FONTTAG_WATERMARK] = font.deriveFont(this.fonts[FONTTAG_WATERMARK].getSize());
            } else {
               this.fonts[FONTTAG_DEFAULT] = font;
               this.fonts[FONTTAG_TITLE] = null;
               this.fonts[FONTTAG_AXIS] = null;
               this.fonts[FONTTAG_UNIT] = null;
               this.fonts[FONTTAG_LEGEND] = null;
               this.fonts[FONTTAG_WATERMARK] = null;
            }
        } else {
            this.fonts[fontTag] = font;
        }
    }

    /**
     * Sets font.
     *
     * @param fontTag Font tag as String, as explained in {@link RrdGraphDef#setFont setFont(int, java.awt.Font)}.
     * @param font Font to be used for tag
     * @throws RrdException Thrown if invalid fontTag is supplied.
     */
    public void setFont(final String fontTag, final Font font) throws RrdException {
        this.setFont(getFontTagByName(fontTag), font);
    }

    /**
     * Sets font.
     *
     * @param fontTag Font tag as String, as explained in {@link RrdGraphDef#setFont setFont(int, java.awt.Font)}.
     * @param font Font to be used for tag
     * @param setAll Boolean to flag whether to set all fonts if fontTag == FONTTAG_DEFAULT
     * @throws RrdException Thrown if invalid fontTag is supplied.
     */
    public void setFont(final String fontTag, final Font font, final boolean setAll) throws RrdException {
        this.setFont(getFontTagByName(fontTag), font, setAll);
    }

    /**
     * Sets font.
     *
     * @param fontTag Font tag as String, as explained in {@link RrdGraphDef#setFont setFont(int, java.awt.Font)}.
     * @param font Font to be used for tag
     * @param setAll Boolean to flag whether to set all fonts if fontTag == FONTTAG_DEFAULT
     * @param keepSizes Boolean to flag whether to keep original font sizes if setting all fonts.
     * @throws RrdException Thrown if invalid fontTag is supplied.
     */
    public void setFont(final String fontTag, final Font font, final boolean setAll, final boolean keepSizes) throws RrdException {
        this.setFont(getFontTagByName(fontTag), font, setAll, keepSizes);
    }

    private static int getFontTagByName(String tagName) throws RrdException {
        for (int i = 0; i < FONTTAG_NAMES.length; i++) {
            if (FONTTAG_NAMES[i].equalsIgnoreCase(tagName)) {
                return i;
            }
        }
        throw new RrdException("Unknown tag name specified: " + tagName);
    }

    public Font getFont(int tag) {
        return this.fonts[tag] == null ? this.fonts[FONTTAG_DEFAULT] : this.fonts[tag];
    }

    /**
     * Defines virtual datasource. This datasource can then be used
     * in other methods like {@link #datasource(String, String)} or
     * {@link #gprint(String, String, String)}.
     *
     * @param name      Source name
     * @param rrdPath   Path to RRD file
     * @param dsName    Datasource name in the specified RRD file
     * @param consolFun Consolidation function (AVERAGE, MIN, MAX, LAST)
     */
    public void datasource(String name, String rrdPath, String dsName, String consolFun) {
        sources.add(new Def(name, rrdPath, dsName, consolFun));
    }

    /**
     * Defines virtual datasource. This datasource can then be used
     * in other methods like {@link #datasource(String, String)} or
     * {@link #gprint(String, String, String)}.
     *
     * @param name      Source name
     * @param rrdPath   Path to RRD file
     * @param dsName    Datasource name in the specified RRD file
     * @param consolFun Consolidation function (AVERAGE, MIN, MAX, LAST)
     * @param backend   Backend to be used while fetching data from a RRD file.
     */
    public void datasource(String name, String rrdPath, String dsName, String consolFun, String backend) {
        sources.add(new Def(name, rrdPath, dsName, consolFun, backend));
    }

    /**
     * Create a new virtual datasource by evaluating a mathematical
     * expression, specified in Reverse Polish Notation (RPN).
     *
     * @param name          Source name
     * @param rpnExpression RPN expression.
     */
    public void datasource(String name, String rpnExpression) {
        sources.add(new CDef(name, rpnExpression));
    }

    /**
     * Creates a new (static) virtual datasource. The value of the datasource is constant. This value is
     * evaluated by applying the given consolidation function to another virtual datasource.
     *
     * @param name      Source name
     * @param defName   Other source name
     * @param consolFun Consolidation function to be applied to other datasource.
     */
    public void datasource(String name, String defName, String consolFun) {
        sources.add(new SDef(name, defName, consolFun));
    }

    /**
     * Creates a new (plottable) datasource. Datasource values are obtained from the given plottable
     * object.
     *
     * @param name      Source name.
     * @param plottable Plottable object.
     */
    public void datasource(String name, Plottable plottable) {
        sources.add(new PDef(name, plottable));
    }

    /**
     * Creates a new static virtual datasource that performs a percentile calculation on an
     * another named datasource to yield a single value.
     * <p>
     * Requires that the other datasource has already been defined otherwise it throws an exception
     * (we need to look at the existing data source to extract the required data)
     *
     * @param name       - the new virtual datasource name
     * @param sourceName - the datasource from which to extract the percentile.  Must be a previously
     *                   defined virtula datasource
     * @param percentile - the percentile to extract from the source datasource
     */
    public void datasource(String name, String sourceName, double percentile) {
        sources.add(new PercentileDef(name, sourceName, percentile));
    }

    /**
     * Creates a new static virtual datasource that performs a percentile calculation on an
     * another named datasource to yield a single value.
     * <p>
     * Requires that the other datasource has already been defined otherwise it throws an exception
     * (we need to look at the existing data source to extract the required data)
     *
     * @param name       - the new virtual datasource name
     * @param sourceName - the datasource from which to extract the percentile.  Must be a previously
     *                   defined virtula datasource
     * @param percentile - the percentile to extract from the source datasource
     * @param includenan - whether to include NaNs in the percentile calculations.
     */
    public void datasource(String name, String sourceName, double percentile, boolean includenan) {
        sources.add(new PercentileDef(name, sourceName, percentile, includenan));
    }

    /**
     * Calculates the chosen consolidation function CF over the given datasource
     * and creates the result by using the given format string.  In
     * the format string there should be a '%[l]f', '%[l]g' or '%[l]e' marker in
     * the place where the number should be printed.
     * <p>
     * If an additional '%s' is found AFTER the marker, the value will be
     * scaled and an appropriate SI magnitude unit will be printed in
     * place of the '%s' marker. The scaling will take the '--base' argument
     * into consideration!
     * <p>
     * If a '%S' is used instead of a '%s', then instead of calculating
     * the appropriate SI magnitude unit for this value, the previously
     * calculated SI magnitude unit will be used.  This is useful if you
     * want all the values in a print statement to have the same SI magnitude
     * unit.  If there was no previous SI magnitude calculation made,
     * then '%S' behaves like a '%s', unless the value is 0, in which case
     * it does not remember a SI magnitude unit and a SI magnitude unit
     * will only be calculated when the next '%s' is seen or the next '%S'
     * for a non-zero value.
     * <p>
     * Print results are collected in the {@link RrdGraphInfo} object which is retrieved
     * from the {@link RrdGraph object} once the graph is created.
     *
     * @param srcName   Virtual source name
     * @param consolFun Consolidation function to be applied to the source
     * @param format    Format string (like "average = %10.3f %s")
     */
    public void print(String srcName, String consolFun, String format) {
        comments.add(new PrintText(srcName, consolFun, format, false));
    }

    /**
     * This method does basically the same thing as {@link #print(String, String, String)},
     * but the result is printed on the graph itself, below the chart area.
     *
     * @param srcName   Virtual source name
     * @param consolFun Consolidation function to be applied to the source
     * @param format    Format string (like "average = %10.3f %s")
     */
    public void gprint(String srcName, String consolFun, String format) {
        comments.add(new PrintText(srcName, consolFun, format, true));
    }

    /**
     * Comment to be printed on the graph.
     *
     * @param text Comment text
     */
    public void comment(String text) {
        comments.add(new CommentText(text));
    }

    /**
     * Draws a horizontal rule into the graph and optionally adds a legend
     *
     * @param value  Position of the rule
     * @param color  Rule color
     * @param legend Legend text. If null, legend text will be omitted.
     */
    public void hrule(double value, Paint color, String legend) {
        hrule(value, color, legend, 1.0F);
    }

    /**
     * Draws a horizontal rule into the graph and optionally adds a legend
     *
     * @param value  Position of the rule
     * @param color  Rule color
     * @param legend Legend text. If null, legend text will be omitted.
     * @param width  Rule width
     */
    public void hrule(double value, Paint color, String legend, float width) {
        LegendText legendText = new LegendText(color, legend);
        comments.add(legendText);
        plotElements.add(new HRule(value, color, legendText, width));
    }

    /**
     * Draws a vertical rule into the graph and optionally adds a legend
     *
     * @param timestamp Position of the rule (seconds since epoch)
     * @param color     Rule color
     * @param legend    Legend text. Use null to omit the text.
     */
    public void vrule(long timestamp, Paint color, String legend) {
        vrule(timestamp, color, legend, 1.0F);
    }

    /**
     * Draws a vertical rule into the graph and optionally adds a legend
     *
     * @param timestamp Position of the rule (seconds since epoch)
     * @param color     Rule color
     * @param legend    Legend text. Use null to omit the text.
     * @param width     Rule width
     */
    public void vrule(long timestamp, Paint color, String legend, float width) {
        LegendText legendText = new LegendText(color, legend);
        comments.add(legendText);
        plotElements.add(new VRule(timestamp, color, legendText, width));
    }

    /**
     * Plots requested data as a line, using the color and the line width specified.
     *
     * @param srcName Virtual source name
     * @param color   Line color
     * @param legend  Legend text
     * @param width   Line width (default: 1.0F)
     */
    public void line(String srcName, Paint color, String legend, float width) {
        if (legend != null) {
            comments.add(new LegendText(color, legend));
        }
        plotElements.add(new Line(srcName, color, width));
    }

    /**
     * Plots requested data as a line, using the color specified. Line width is assumed to be
     * 1.0F.
     *
     * @param srcName Virtual source name
     * @param color   Line color
     * @param legend  Legend text
     */
    public void line(String srcName, Paint color, String legend) {
        line(srcName, color, legend, 1F);
    }

    /**
     * Plots requested data in the form of the filled area starting from zero,
     * using the color specified.
     *
     * @param srcName Virtual source name.
     * @param color   Color of the filled area.
     * @param legend  Legend text.
     */
    public void area(String srcName, Paint color, String legend) {
        area(srcName, color);
        if ((legend != null) && (legend.length() > 0)) {
            LegendText legendText = new LegendText(color, legend);
            comments.add(legendText);
        }
    }

    /**
     * Plots requested data in the form of the filled area starting from zero,
     * using the color specified.
     *
     * @param srcName Virtual source name.
     * @param color   Color of the filled area.
     */
    public void area(String srcName, Paint color) {
        plotElements.add(new Area(srcName, color));
    }

    /**
     * Does the same as {@link #line(String, java.awt.Paint, String)},
     * but the graph gets stacked on top of the
     * previous LINE, AREA or STACK graph. Depending on the type of the
     * previous graph, the STACK will be either a LINE or an AREA.  This
     * obviously implies that the first STACK must be preceded by an AREA
     * or LINE.
     * <p>
     * Note, that when you STACK onto *UNKNOWN* data, JRobin will not
     * draw any graphics ... *UNKNOWN* is not zero.
     *
     * @param srcName Virtual source name
     * @param color   Stacked graph color
     * @param legend  Legend text
     * @throws RrdException Thrown if this STACK has no previously defined AREA, STACK or LINE
     *                      graph bellow it.
     */
    public void stack(String srcName, Paint color, String legend) throws RrdException {
        // find parent AREA or LINE
        SourcedPlotElement parent = null;
        for (int i = plotElements.size() - 1; i >= 0; i--) {
            PlotElement plotElement = plotElements.get(i);
            if (plotElement instanceof SourcedPlotElement) {
                parent = (SourcedPlotElement) plotElement;
                break;
            }
        }
        if (parent == null) {
            throw new RrdException("You have to stack graph onto something (line or area)");
        }
        else {
            LegendText legendText = new LegendText(color, legend);
            comments.add(legendText);
            plotElements.add(new Stack(parent, srcName, color));
        }
    }

    /**
     * Sets visibility of the X-axis grid.
     *
     * @param drawXGrid True if X-axis grid should be created (default), false otherwise.
     */
    public void setDrawXGrid(boolean drawXGrid) {
        this.drawXGrid = drawXGrid;
    }

    /**
     * Sets visibility of the Y-axis grid.
     *
     * @param drawYGrid True if Y-axis grid should be created (default), false otherwise.
     */
    public void setDrawYGrid(boolean drawYGrid) {
        this.drawYGrid = drawYGrid;
    }

    /**
     * Sets image quality. Relevant only for JPEG images.
     *
     * @param imageQuality (0F=worst, 1F=best).
     */
    public void setImageQuality(float imageQuality) {
        this.imageQuality = imageQuality;
    }

    /**
     * Controls if the chart area of the image should be antialiased or not.
     *
     * @param antiAliasing use true to turn antialiasing on, false to turn it off (default)
     */
    public void setAntiAliasing(boolean antiAliasing) {
        this.antiAliasing = antiAliasing;
    }

    /**
     * Shows or hides graph signature (gator) in the top right corner of the graph
     *
     * @param showSignature true, if signature should be seen (default), false otherwise
     */
    public void setShowSignature(boolean showSignature) {
        this.showSignature = showSignature;
    }

    /**
     * Sets first day of the week.
     *
     * @param firstDayOfWeek One of the following constants:
     *                       {@link RrdGraphConstants#MONDAY MONDAY},
     *                       {@link RrdGraphConstants#TUESDAY TUESDAY},
     *                       {@link RrdGraphConstants#WEDNESDAY WEDNESDAY},
     *                       {@link RrdGraphConstants#THURSDAY THURSDAY},
     *                       {@link RrdGraphConstants#FRIDAY FRIDAY},
     *                       {@link RrdGraphConstants#SATURDAY SATURDAY},
     *                       {@link RrdGraphConstants#SUNDAY SUNDAY}
     */
    public void setFirstDayOfWeek(int firstDayOfWeek) {
        this.firstDayOfWeek = firstDayOfWeek;
    }

    // helper methods

    int printStatementCount() {
        int count = 0;
        for (CommentText comment : comments) {
            if (comment instanceof PrintText) {
                if (comment.isPrint()) {
                    count++;
                }
            }
        }
        return count;
    }

    boolean shouldPlot() {
        if (plotElements.size() > 0) {
            return true;
        }
        for (CommentText comment : comments) {
            if (comment.isValidGraphElement()) {
                return true;
            }
        }
        return false;
    }
}
