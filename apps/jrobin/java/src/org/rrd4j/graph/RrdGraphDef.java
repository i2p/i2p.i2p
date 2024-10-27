package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Paint;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import java.util.function.Function;

import javax.imageio.ImageIO;

import org.rrd4j.ConsolFun;
import org.rrd4j.core.DataHolder;
import org.rrd4j.core.FetchData;
import org.rrd4j.core.RrdBackendFactory;
import org.rrd4j.core.RrdDbPool;
import org.rrd4j.core.Util;
import org.rrd4j.data.DataProcessor;
import org.rrd4j.data.IPlottable;
import org.rrd4j.data.Variable;

/**
 * <p>Class which should be used to define new Rrd4j graph. Once constructed and populated with data
 * object of this class should be passed to the constructor of the {@link org.rrd4j.graph.RrdGraph} class which
 * will actually create the graph.</p>
 * 
 * <p>The text printed below the actual graph can be formated by appending
 * special escaped characters at the end of a text. When ever such a
 * character occurs, all pending text is pushed onto the graph according to
 * the character specified.</p>
 * 
 * <p>Valid markers are: \j for justified, \l for left aligned, \r for right
 * aligned and \c for centered.</p>
 * 
 * <p>Normally there are two space characters inserted between every two
 * items printed into the graph. The space following a string can be
 * suppressed by putting a \g at the end of the string. The \g also squashes
 * any space inside the string if it is at the very end of the string.
 * This can be used in connection with %s to suppress empty unit strings.</p>
 * 
 * <p>A special case is COMMENT:\s this inserts some additional vertical
 * space before placing the next row of legends.</p>
 * 
 * <p>When text has to be formated without special instructions from your
 * side, RRDTool will automatically justify the text as soon as one string
 * goes over the right edge. If you want to prevent the justification
 * without forcing a newline, you can use the special tag \J at the end of
 * the string to disable the auto justification.</p>
 */
public class RrdGraphDef implements RrdGraphConstants, DataHolder {

    /**
     * <p>Implementations of this class can be used to generate image than can be
     * layered on graph. The can be used for background image, a background image
     * draw on canvas or an overlay image.</p>
     * @author Fabrice Bacchella
     *
     */
    public interface ImageSource {
        /**
         * A image of the required size that will be applied. If the generated image is too big, it will be clipped before being applied.
         * @param w the width of the requested image
         * @param h the high of the requested image
         * @return an image to draw.
         * @throws IOException
         */
        BufferedImage apply(int w, int h) throws IOException;
    }

    private static class FileImageSource implements ImageSource {
        private final File imagesource;

        FileImageSource(String imagesource) {
            this.imagesource = new File(imagesource);
        }

        public BufferedImage apply(int w, int h) throws IOException {
            return ImageIO.read(imagesource);
        }
    }

    private static class UrlImageSource implements ImageSource {
        private final URL imagesource;

        private UrlImageSource(URL imagesource) {
            this.imagesource = imagesource;
        }

        public BufferedImage apply(int w, int h) throws IOException {
            return ImageIO.read(imagesource);
        }
    }

    boolean poolUsed = DEFAULT_POOL_USAGE_POLICY;
    private RrdDbPool pool = null;
    boolean antiAliasing = false; // ok
    boolean textAntiAliasing = false; // ok
    String filename = RrdGraphConstants.IN_MEMORY_IMAGE; // ok
    long startTime, endTime; // ok
    TimeAxisSetting timeAxisSetting = null; // ok
    TimeLabelFormat timeLabelFormat = null;
    Function<TimeUnit, Optional<TimeLabelFormat>> formatProvider = s -> Optional.empty();
    ValueAxisSetting valueAxisSetting = null; // ok
    boolean altYGrid = false; // ok
    boolean noMinorGrid = false; // ok
    boolean altYMrtg = false; // ok
    boolean altAutoscale = false; // ok
    boolean altAutoscaleMin = false; // ok
    boolean altAutoscaleMax = false; // ok
    int unitsExponent = Integer.MAX_VALUE; // ok
    int unitsLength = DEFAULT_UNITS_LENGTH; // ok
    String verticalLabel = null; // ok
    int width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT; // ok
    boolean interlaced = false; // ok
    String imageInfo = null; // ok
    String imageFormat = DEFAULT_IMAGE_FORMAT; // ok
    float imageQuality = DEFAULT_IMAGE_QUALITY; // ok
    ImageSource backgroundImage = null; // ok
    ImageSource canvasImage = null; // ok
    ImageSource overlayImage = null; // ok
    String unit = null; // ok
    boolean lazy = false; // ok
    double minValue = Double.NaN; // ok
    double maxValue = Double.NaN; // ok
    boolean rigid = false; // ok
    double base = DEFAULT_BASE;  // ok
    boolean logarithmic = false; // ok
    private final Paint[] colors = new Paint[]{
            // ok
            DEFAULT_CANVAS_COLOR,
            DEFAULT_BACK_COLOR,
            DEFAULT_SHADEA_COLOR,
            DEFAULT_SHADEB_COLOR,
            DEFAULT_GRID_COLOR,
            DEFAULT_MGRID_COLOR,
            DEFAULT_FONT_COLOR,
            DEFAULT_FRAME_COLOR,
            DEFAULT_ARROW_COLOR,
            DEFAULT_XAXIS_COLOR,
            DEFAULT_YAXIS_COLOR
    };
    boolean noLegend = false; // ok
    boolean onlyGraph = false; // ok
    boolean forceRulesLegend = false; // ok
    String title = null; // ok
    long step = 0; // ok
    Font[] fonts = new Font[] {
            DEFAULT_SMALL_FONT,    // FONTTAG_DEFAULT
            DEFAULT_LARGE_FONT,    // FONTTAG_TITLE
            DEFAULT_SMALL_FONT,    // FONTTAG_AXIS
            DEFAULT_SMALL_FONT,    // FONTTAG_UNIT
            DEFAULT_SMALL_FONT,    // FONTTAG_LEGEND
            GATOR_FONT             // FONTTAG_WATERMARK
    };
    boolean drawXGrid = true; // ok
    boolean drawYGrid = true; // ok
    int firstDayOfWeek = FIRST_DAY_OF_WEEK; // ok
    Locale locale = Locale.getDefault();
    TimeZone tz = TimeZone.getDefault();
    String signature = "Generated by RRD4J";
    boolean showSignature = true;
    Stroke gridStroke = GRID_STROKE;
    Stroke tickStroke = TICK_STROKE;
    DownSampler downsampler = null;

    final List<Source> sources = new ArrayList<>();
    final List<CommentText> comments = new ArrayList<>();
    final List<PlotElement> plotElements = new ArrayList<>();

    /**
     * Creates RrdGraphDef object and sets default time span (default ending time is 'now',
     * default starting time is 'end-1day'.
     * @deprecated Uses default value that will be probably overriden.
     */
    @Deprecated
    public RrdGraphDef() {
        setTimeSpan(Util.getTimestamps(DEFAULT_START, DEFAULT_END));
    }

    /**
     * Creates RrdGraphDef object.
     * @since 3.7
     */
    public RrdGraphDef(long t1, long t2) {
        if ((t1 < t2 && t1 > 0 && t2 > 0) || (t1 > 0 && t2 == 0)) {
            this.startTime = t1;
            this.endTime = t2;
        }
        else {
            throw new IllegalArgumentException("Invalid timestamps specified: " + t1 + ", " + t2);
        }
    }

    /**
     * Creates new DataProcessor object for the given time duration. The given duration will be
     * substracted from current time.
     *
     * @param d duration to substract.
     * @since 3.7
     */
    public RrdGraphDef(TemporalAmount d) {
        Instant now = Instant.now();
        this.endTime = now.getEpochSecond();
        this.startTime = now.minus(d).getEpochSecond();
    }

    /**
     * Sets the time when the graph should begin. Time in seconds since epoch
     * (1970-01-01) is required. Negative numbers are relative to the current time.
     *
     * @param time Starting time for the graph in seconds since epoch
     */
    @Override
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
    @Override
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
    @Override
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
     * {@link org.rrd4j.core.RrdDbPool RrdDbPool} will be used to
     * access individual RRD files. If set to false, RRD files will be accessed directly.
     *
     * @param poolUsed true, if RrdDbPool class should be used. False otherwise.
     */
    @Override
    public void setPoolUsed(boolean poolUsed) {
        this.poolUsed = poolUsed;
    }

    /**
     * @since 3.7
     */
    @Override
    public boolean isPoolUsed() {
        return poolUsed;
    }

    /**
     * @since 3.7
     */
    @Override
    public RrdDbPool getPool() {
        return pool;
    }

    /**
     * @since 3.7
     */
    @Override
    public void setPool(RrdDbPool pool) {
        this.poolUsed = true;
        this.pool = pool;
    }

    /**
     * Sets the name of the graph to generate. Since Rrd4j outputs GIFs, PNGs,
     * and JPEGs it's recommended that the filename end in either .gif,
     * .png or .jpg. Rrd4j does not enforce this, however. If the filename is
     * set to '-' the image will be created only in memory (no file will be created).
     * PNG and GIF formats are recommended but JPEGs should be avoided.
     *
     * @param filename Path to the image file
     */
    public void setFilename(String filename) {
        this.filename = filename;
    }

    /**
     * <p>Configures x-axis grid and labels. The x-axis label is quite complex to configure.
     * So if you don't have very special needs, you can rely on the autoconfiguration to
     * get this right.</p>
     * 
     * <p>Otherwise, you have to configure three elements making up the x-axis labels
     * and grid. The base grid, the major grid and the labels.
     * The configuration is based on the idea that you first specify a well
     * known amount of time and then say how many times
     * it has to pass between each minor/major grid line or label. For the label
     * you have to define two additional items: The precision of the label
     * in seconds and the format used to generate the text
     * of the label.</p>
     * 
     * <p>For example, if you wanted a graph with a base grid every 10 minutes and a major
     * one every hour, with labels every hour you would use the following
     * x-axis definition.</p>
     * 
     * <pre>
     * setTimeAxis(RrdGraphConstants.MINUTE, 10,
     *             RrdGraphConstants.HOUR, 1,
     *             RrdGraphConstants.HOUR, 1,
     *             0, "%H:%M")
     * </pre>
     * 
     * <p>The precision in this example is 0 because the %X format is exact.
     * If the label was the name of the day, we would have had a precision
     * of 24 hours, because when you say something like 'Monday' you mean
     * the whole day and not Monday morning 00:00. Thus the label should
     * be positioned at noon. By defining a precision of 24 hours or
     * rather 86400 seconds, you make sure that this happens.</p>
     *
     * @param minorUnit        Minor grid unit. Minor grid, major grid and label units
     *                         can be one of the following constants defined in
     *                         {@link org.rrd4j.graph.RrdGraphConstants}: {@link org.rrd4j.graph.RrdGraphConstants#SECOND SECOND},
     *                         {@link org.rrd4j.graph.RrdGraphConstants#MINUTE MINUTE}, {@link org.rrd4j.graph.RrdGraphConstants#HOUR HOUR},
     *                         {@link org.rrd4j.graph.RrdGraphConstants#DAY DAY}, {@link org.rrd4j.graph.RrdGraphConstants#WEEK WEEK},
     *                         {@link org.rrd4j.graph.RrdGraphConstants#MONTH MONTH}, {@link org.rrd4j.graph.RrdGraphConstants#YEAR YEAR}.
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
                labelUnit, labelUnitCount, labelSpan, new SimpleTimeLabelFormat(simpleDateFormat));
    }

    /**
     * It configure the x-axis grid in the same way than {@link #setTimeAxis(int, int, int, int, int, int, int, String)}, but it allows
     * to use a {@link org.rrd4j.graph.TimeLabelFormat} to format the date label.
     * 
     * @param minorUnit
     * @param minorUnitCount
     * @param majorUnit
     * @param majorUnitCount
     * @param labelUnit
     * @param labelUnitCount
     * @param labelSpan
     * @param format
     */
    public void setTimeAxis(int minorUnit, int minorUnitCount, int majorUnit, int majorUnitCount,
            int labelUnit, int labelUnitCount, int labelSpan, TimeLabelFormat format) {
        timeAxisSetting = new TimeAxisSetting(minorUnit, minorUnitCount, majorUnit, majorUnitCount,
                labelUnit, labelUnitCount, labelSpan, format);
    }

    /**
     * This allows to keep the default major and minor grid unit, but with changing only the label formatting,
     * using a {@link org.rrd4j.graph.TimeLabelFormat}
     * 
     * @param format a custom dynamic time label format
     */
    public void setTimeLabelFormat(TimeLabelFormat format) {
        timeLabelFormat = format;
    }

    /**
     * <p>This allows to keep the default major and minor grid unit, but with changing only the label formatting,
     * that will be formatted differently according to {@link TimeUnit} chosen for the time axis.</p>
     * <p>If the returned {@link Optional} is empty, the default formatting will be kept</p>
     * <table border="1">
     *     <caption>Default formatting</caption>
     *   <thead>
     *     <tr>
     *       <th>{@link TimeUnit}</th>
     *       <th>Default pattern</th>
     *     </tr>
     *   </thead>
     *   <tbody>
     *     <tr>
     *       <td>MINUTE</td>
     *       <td>HH:mm</td>
     *     </tr>
     *     <tr>
     *       <td>HOUR</td>
     *       <td>HH:mm</td>
     *     </tr>
     *     <tr>
     *       <td>DAY</td>
     *       <td>EEE dd</td>
     *     </tr>
     *     <tr>
     *       <td>WEEK</td>
     *       <td>'Week 'w</td>
     *     </tr>
     *     <tr>
     *       <td>MONTH</td>
     *       <td>MMM</td>
     *     </tr>
     *     <tr>
     *       <td>YEAR</td>
     *       <td>yy</td>
     *     </tr>
     *   </tbody>
     * </table>
     *
     *
     * @param formatProvider An {@link Optional} holding the {@link TimeLabelFormat} to use or empy to keep the default.
     * @since 3.10
     */
    public void setTimeLabelFormatter(Function<TimeUnit, Optional<TimeLabelFormat>> formatProvider) {
        this.formatProvider = formatProvider;
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
     * @param altAutoscaleMin true to request alternative autoscaling, false
     *                        otherwise (default)
     */
    public void setAltAutoscaleMin(boolean altAutoscaleMin) {
        this.altAutoscaleMin = altAutoscaleMin;
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
     * be an integer which is a multiple of 3 between -18 and 18, inclusive.
     * It is the exponent on the units you which to use.  For example,
     * use 3 to display the y-axis values in k (Kilo, 10e3, thousands),
     * use -6 to display the y-axis values in µ (Micro, 10e-6,
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
     * Creates interlaced or progressive mode image.
     *
     * @param interlaced true, if GIF image should be interlaced.
     */
    public void setInterlaced(boolean interlaced) {
        this.interlaced = interlaced;
    }

    /**
     * <p>Creates additional image information.
     * After the image has been created, the graph function uses imageInfo
     * format string (printf-like) to create output similar to
     * the {@link #print(String, ConsolFun, String)} function.
     * The format string is supplied with the following parameters:
     * filename, xsize and ysize (in that particular order).</p>
     * 
     * <p>For example, in order to generate an IMG tag
     * suitable for including the graph into a web page, the command
     * would look like this:</p>
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
     * ImageIO is used to save the image, so any supported format by ImageIO can be used, and it can be extended using <a href="https://github.com/geosolutions-it/imageio-ext">...</a>.
     *
     * @param imageFormat Any value as return by {@link ImageIO#getReaderFormatNames}
     */
    public void setImageFormat(String imageFormat) {
        this.imageFormat = imageFormat;
    }

    /**
     * Sets background image.
     * ImageIO is used to download, so any supported format by ImageIO can be used, and it can be extended using <a href="https://github.com/geosolutions-it/imageio-ext">...</a>.
     *
     * @param backgroundImage Path to background image
     */
    public void setBackgroundImage(String backgroundImage) {
        this.backgroundImage = new FileImageSource(backgroundImage);
    }

    /**
     * Sets background image.
     * ImageIO is used to download, so any supported format by ImageIO can be used, and it can be extended using <a href="https://github.com/geosolutions-it/imageio-ext">...</a>.
     *
     * @param backgroundImageUrl URL to background image
     */
    public void setBackgroundImage(URL backgroundImageUrl) {
        this.backgroundImage = new UrlImageSource(backgroundImageUrl);
    }

    /**
     * Sets background image.
     *
     * @param backgroundImage An {@link ImageSource} that will provides a {@link BufferedImage}
     */
    public void setBackgroundImage(ImageSource backgroundImage) {
        this.backgroundImage = backgroundImage;
    }

    /**
     * Sets canvas background image. Canvas image is printed on canvas area, under canvas color and plot.
     * ImageIO is used to download, so any supported format by ImageIO can be used, and it can be extended using <a href="https://github.com/geosolutions-it/imageio-ext">...</a>.
     *
     * @param canvasImage Path to canvas image
     */
    public void setCanvasImage(String canvasImage) {
        this.canvasImage = new FileImageSource(canvasImage);
    }

    /**
     * Sets canvas background image. Canvas image is printed on canvas area, under canvas color and plot.
     * ImageIO is used to download, so any supported format by ImageIO can be used, and it can be extended using <a href="https://github.com/geosolutions-it/imageio-ext">...</a>.
     *
     * @param canvasUrl URL to canvas image
     */
    public void setCanvasImage(URL canvasUrl) {
        this.canvasImage = new UrlImageSource(canvasUrl);
    }

    /**
     * Sets canvas background image. Canvas image is printed on canvas area, under canvas color and plot.
     *
     * @param canvasImageSource An {@link ImageSource} that will provides a {@link BufferedImage}
     */
    public void setCanvasImage(ImageSource canvasImageSource) {
        this.canvasImage = canvasImageSource;
    }

    /**
     * Sets overlay image. Overlay image is printed on the top of the image, once it is completely created.
     * ImageIO is used to download, so any supported format by ImageIO can be used, and it can be extended using <a href="https://github.com/geosolutions-it/imageio-ext">...</a>.
     *
     * @param overlayImage Path to overlay image
     */
    public void setOverlayImage(String overlayImage) {
        this.overlayImage = new FileImageSource(overlayImage);
    }

    /**
     * Sets overlay image. Overlay image is printed on the top of the image, once it is completely created.
     * ImageIO is used to download, so any supported format by ImageIO can be used, and it can be extended using <a href="https://github.com/geosolutions-it/imageio-ext">...</a>.
     *
     * @param overlayImage URL to overlay image
     */
    public void setOverlayImage(URL overlayImage) {
        this.overlayImage = new UrlImageSource(overlayImage);
    }

    /**
     * Sets overlay image. Overlay image is printed on the top of the image, once it is completely created.
     *
     * @param overlayImageSource An {@link ImageSource} that will provides a {@link BufferedImage}
     */
    public void setOverlayImage(ImageSource overlayImageSource) {
        this.overlayImage = overlayImageSource;
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
     * @param lazy true, if graph should be 'lazy', false otherwise (default)
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
     * <p>Defines the value normally located at the upper border of the
     * graph. If the graph contains higher values, the upper border will
     * move upwards to accommodate these values as well.</p>
     * 
     * <p>If you want to define an upper-limit which will not move in any
     * event you have to use {@link #setRigid(boolean)} method as well.</p>
     *
     * @param maxValue Maximal value displayed on the graph.
     */
    public void setMaxValue(double maxValue) {
        this.maxValue = maxValue;
    }

    /**
     * Sets rigid boundaries mode. Normally Rrd4j will automatically expand
     * the lower and upper limit if the graph contains a value outside the
     * valid range. With the <code>true</code> argument you can disable this behavior.
     *
     * @param rigid true if upper and lower limits should not be expanded to accommodate
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
     * Overrides the colors for the standard elements of the graph. The
     * colorTag must be one of the following constants defined in the {@link org.rrd4j.graph.RrdGraphConstants}:
     * {@link org.rrd4j.graph.RrdGraphConstants#COLOR_BACK COLOR_BACK}ground,
     * {@link org.rrd4j.graph.RrdGraphConstants#COLOR_CANVAS COLOR_CANVAS},
     * {@link org.rrd4j.graph.RrdGraphConstants#COLOR_XAXIS COLOR_XAXIS},
     * {@link org.rrd4j.graph.RrdGraphConstants#COLOR_SHADEA COLOR_SHADEA} left/top border,
     * {@link org.rrd4j.graph.RrdGraphConstants#COLOR_SHADEB COLOR_SHADEB} right/bottom border,
     * {@link org.rrd4j.graph.RrdGraphConstants#COLOR_GRID COLOR_GRID},
     * {@link org.rrd4j.graph.RrdGraphConstants#COLOR_MGRID COLOR_MGRID} major grid,
     * {@link org.rrd4j.graph.RrdGraphConstants#COLOR_FONT COLOR_FONT},
     * {@link org.rrd4j.graph.RrdGraphConstants#COLOR_FRAME COLOR_FRAME} and axis of the graph or
     * {@link org.rrd4j.graph.RrdGraphConstants#COLOR_ARROW COLOR_ARROW}. This
     * method can be called multiple times to set several colors.
     *
     * @param colorTag Color tag, as explained above.
     * @param color    Any color (paint) you like
     * @deprecated Using {@link #setColor(ElementsNames, Paint)}
     */
    @Deprecated
    public void setColor(int colorTag, Paint color) {
        if (colorTag >= 0 && colorTag < colors.length) {
            colors[colorTag] = color;
        } else {
            throw new IllegalArgumentException("Invalid color index specified: " + colorTag);
        }
    }

    /**
     * Overrides the colors for the standard elements of the graph.
     * @param colorTag The element to change color.
     * @param color The color of the element.
     */
    public void setColor(ElementsNames colorTag, Paint color) {
        colors[colorTag.ordinal()] = color;
    }

    /**
     * Overrides the colors for the standard elements of the graph by element name.
     * See {@link #setColor(int, java.awt.Paint)} for full explanation.
     *
     * @param colorName One of the following strings: "BACK", "CANVAS", "SHADEA", "SHADEB",
     *                  "GRID", "MGRID", "FONT", "FRAME", "ARROW", "XAXIS", "YAXIS"
     * @param color     Any color (paint) you like
     * @deprecated Using {@link #setColor(ElementsNames, Paint)}
     */
    @Deprecated
    public void setColor(String colorName, Paint color) {
        setColor(ElementsNames.valueOf(colorName.toLowerCase(Locale.ENGLISH)).ordinal(), color);
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
     * Suggests which time step should be used by Rrd4j while processing data from RRD files.
     *
     * @param step Desired time step (don't use this method if you don't know what you're doing).
     */
    @Override
    public void setStep(long step) {
        this.step = step;
    }

    /**
     * This method reset the font set to it's default values. With the flag rrdtool set to true, it's not the old
     * default set that is used, but the one taken from rrdtool. So use false to keep compatibility with previous version
     * and true for a graph matching rrdtool's
     * 
     * @param rrdtool true to use rrdtool font set
     */
    public void setFontSet(boolean rrdtool) {
        if(rrdtool) {
            // We add a factor to the font size, rrdtool and java don't agree about font size
            float rrdtoolfactor = 12f/9;
            fonts = new Font[] {
                    DEFAULT_SMALL_FONT.deriveFont(8.0f * rrdtoolfactor),    // FONTTAG_DEFAULT
                    DEFAULT_SMALL_FONT.deriveFont(9.0f * rrdtoolfactor),    // FONTTAG_TITLE
                    DEFAULT_SMALL_FONT.deriveFont(7.0f * rrdtoolfactor),    // FONTTAG_AXIS
                    DEFAULT_SMALL_FONT.deriveFont(8.0f * rrdtoolfactor),    // FONTTAG_UNIT
                    DEFAULT_SMALL_FONT.deriveFont(8.0f * rrdtoolfactor),    // FONTTAG_LEGEND
                    DEFAULT_SMALL_FONT.deriveFont(5.5f * rrdtoolfactor)     // FONTTAG_WATERMARK
            };
        } else {
            fonts = new Font[] {
                    DEFAULT_SMALL_FONT,    // FONTTAG_DEFAULT
                    DEFAULT_LARGE_FONT,    // FONTTAG_TITLE
                    DEFAULT_SMALL_FONT,    // FONTTAG_AXIS
                    DEFAULT_SMALL_FONT,    // FONTTAG_UNIT
                    DEFAULT_SMALL_FONT,    // FONTTAG_LEGEND
                    GATOR_FONT             // FONTTAG_WATERMARK
            };            
        }
    }

    /**
     * Sets default font for graphing. Note that Rrd4j will behave unpredictably if proportional
     * font is selected.
     *
     * @param smallFont Default font for graphing. Use only monospaced fonts.
     * @deprecated Use {@link FontTag} based method instead.
     */
    @Deprecated
    public void setSmallFont(final Font smallFont) {
        this.setFont(FontTag.DEFAULT, smallFont);
    }

    /**
     * Sets title font.
     *
     * @param largeFont Font to be used for graph title.
     * @deprecated Use {@link FontTag} based method instead.
     */
    @Deprecated
    public void setLargeFont(final Font largeFont) {
        this.setFont(FontTag.TITLE, largeFont);
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
     */
    public void setFont(final FontTag fontTag, final Font font) {
        this.setFont(fontTag, font, false);
    }

    /**
     * Sets font.
     *
     * @param fontTag Font tag, as explained above.
     * @param font Font to be used for tag
     * @param setAll Boolean to flag whether to set all fonts if fontTag == FONTTAG_DEFAULT
     */
    public void setFont(final FontTag fontTag, final Font font, final boolean setAll) {
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
    public void setFont(final FontTag fontTag, final Font font, final boolean setAll, final boolean keepSizes) {
        if (fontTag == FontTag.DEFAULT && setAll) {
            if (keepSizes) {
                this.fonts[FONTTAG_DEFAULT.ordinal()] = font.deriveFont(this.fonts[FONTTAG_DEFAULT.ordinal()].getSize());
                this.fonts[FONTTAG_TITLE.ordinal()] = font.deriveFont(this.fonts[FONTTAG_TITLE.ordinal()].getSize());
                this.fonts[FONTTAG_AXIS.ordinal()] = font.deriveFont(this.fonts[FONTTAG_AXIS.ordinal()].getSize());
                this.fonts[FONTTAG_UNIT.ordinal()] = font.deriveFont(this.fonts[FONTTAG_UNIT.ordinal()].getSize());
                this.fonts[FONTTAG_LEGEND.ordinal()] = font.deriveFont(this.fonts[FONTTAG_LEGEND.ordinal()].getSize());
                this.fonts[FONTTAG_WATERMARK.ordinal()] = font.deriveFont(this.fonts[FONTTAG_WATERMARK.ordinal()].getSize());
            }
            else {
                this.fonts[FONTTAG_DEFAULT.ordinal()] = font;
                this.fonts[FONTTAG_TITLE.ordinal()] = null;
                this.fonts[FONTTAG_AXIS.ordinal()] = null;
                this.fonts[FONTTAG_UNIT.ordinal()] = null;
                this.fonts[FONTTAG_LEGEND.ordinal()] = null;
                this.fonts[FONTTAG_WATERMARK.ordinal()] = null;
            }
        } else {
            this.fonts[fontTag.ordinal()] = font;
        }
    }

    /**
     * Sets font.
     *
     * @param fontTag Font tag as String, as explained in {@link #setFont(org.rrd4j.graph.RrdGraphConstants.FontTag, Font, boolean)}.
     * @param font Font to be used for tag
     */
    public void setFont(final String fontTag, final Font font) {
        this.setFont(FontTag.valueOf(fontTag), font);
    }

    /**
     * Sets font.
     *
     * @param fontTag Font tag as String, as explained in {@link #setFont(org.rrd4j.graph.RrdGraphConstants.FontTag, Font, boolean)}.
     * @param font Font to be used for tag
     * @param setAll Boolean to flag whether to set all fonts if fontTag == FONTTAG_DEFAULT
     */
    public void setFont(final String fontTag, final Font font, final boolean setAll) {
        this.setFont(FontTag.valueOf(fontTag), font, setAll);
    }

    /**
     * Sets font.
     *
     * @param fontTag Font tag as String, as explained in {@link #setFont(org.rrd4j.graph.RrdGraphConstants.FontTag, Font, boolean)}.
     * @param font Font to be used for tag
     * @param setAll Boolean to flag whether to set all fonts if fontTag == FONTTAG_DEFAULT
     * @param keepSizes Boolean to flag whether to keep original font sizes if setting all fonts.
     */
    public void setFont(final String fontTag, final Font font, final boolean setAll, final boolean keepSizes) {
        this.setFont(FontTag.valueOf(fontTag), font, setAll, keepSizes);
    }

    public Font getFont(final FontTag tag) {
        return this.fonts[tag.ordinal()] == null ? this.fonts[FONTTAG_DEFAULT.ordinal()] : this.fonts[tag.ordinal()];
    }


    /**
     * Defines virtual datasource. This datasource can then be used
     * in other methods like {@link #datasource(String, String)} or
     * {@link #gprint(String, ConsolFun, String)}.
     *
     * @param name      Source name
     * @param rrdPath   Path to RRD file
     * @param dsName    Datasource name in the specified RRD file
     * @param consolFun Consolidation function (AVERAGE, MIN, MAX, LAST)
     */
    @Override
    public void datasource(String name, String rrdPath, String dsName, ConsolFun consolFun) {
        RrdBackendFactory factory = RrdBackendFactory.getDefaultFactory();
        sources.add(new Def(name, factory.getUri(rrdPath), dsName, consolFun, factory));
    }

    /**
     * Defines virtual datasource. This datasource can then be used
     * in other methods like {@link #datasource(String, String)} or
     * {@link #gprint(String, ConsolFun, String)}.
     *
     * @param name      Source name
     * @param rrdUri    URI to RRD file
     * @param dsName    Datasource name in the specified RRD file
     * @param consolFun Consolidation function (AVERAGE, MIN, MAX, LAST)
     * @since 3.7
     */
    @Override
    public void datasource(String name, URI rrdUri, String dsName,
            ConsolFun consolFun) {
        sources.add(new Def(name, rrdUri, dsName, consolFun, RrdBackendFactory.findFactory(rrdUri)));
    }

    /**
     * Defines virtual datasource. This datasource can then be used
     * in other methods like {@link #datasource(String, String)} or
     * {@link #gprint(String, ConsolFun, String)}.
     *
     * @param name      Source name
     * @param rrdPath   Path to RRD file
     * @param dsName    Datasource name in the specified RRD file
     * @param consolFun Consolidation function (AVERAGE, MIN, MAX, LAST)
     * @param backend   Backend to be used while fetching data from a RRD file.
     * 
     * @deprecated Uses {@link #datasource(String, String, String, ConsolFun, RrdBackendFactory)} instead
     */
    @Deprecated
    public void datasource(String name, String rrdPath, String dsName, ConsolFun consolFun, String backend) {
        RrdBackendFactory factory = RrdBackendFactory.getFactory(backend);
        sources.add(new Def(name, factory.getUri(rrdPath), dsName, consolFun, factory));
    }

    /**
     * Defines virtual datasource. This datasource can then be used
     * in other methods like {@link #datasource(String, String)} or
     * {@link #gprint(String, ConsolFun, String)}.
     *
     * @param name      Source name
     * @param rrdPath   Path to RRD file
     * @param dsName    Datasource name in the specified RRD file
     * @param consolFun Consolidation function (AVERAGE, MIN, MAX, LAST)
     * @param backend   Backend to be used while fetching data from a RRD file.
     */
    @Override
    public void datasource(String name, String rrdPath, String dsName, ConsolFun consolFun, RrdBackendFactory backend) {
        sources.add(new Def(name, backend.getUri(rrdPath), dsName, consolFun, backend));
    }

    /**
     * Defines virtual datasource. This datasource can then be used
     * in other methods like {@link #datasource(String, String)} or
     * {@link #gprint(String, ConsolFun, String)}.
     *
     * @param name      Source name
     * @param rrdUri    Path to RRD file
     * @param dsName    Datasource name in the specified RRD file
     * @param consolFun Consolidation function (AVERAGE, MIN, MAX, LAST)
     * @param backend   Backend to be used while fetching data from a RRD file.
     * @since 3.7
     */
    @Override
    public void datasource(String name, URI rrdUri, String dsName,
            ConsolFun consolFun, RrdBackendFactory backend) {
        sources.add(new Def(name, rrdUri, dsName, consolFun, backend));
    }

    /**
     * Create a new virtual datasource by evaluating a mathematical
     * expression, specified in Reverse Polish Notation (RPN).
     *
     * @param name          Source name
     * @param rpnExpression RPN expression.
     */
    @Override
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
     * @deprecated Use {@link Variable} based method instead.
     */
    @Deprecated
    public void datasource(String name, String defName, ConsolFun consolFun) {
        datasource(name, defName, consolFun.getVariable());
    }

    /**
     * Creates a datasource that performs a variable calculation on an
     * another named datasource to yield a single combined timestamp/value.
     * <p>
     * Requires that the other datasource has already been defined; otherwise, it'll
     * end up with no data
     *
     * @param name - the new virtual datasource name
     * @param defName - the datasource from which to extract the percentile. Must be a previously
     *                     defined virtual datasource
     * @param var - a new instance of a Variable used to do the calculation
     */
    @Override
    public void datasource(String name, String defName, Variable var) {
        sources.add(new VDef(name, defName, var));
    }

    /**
     * Creates a new (plottable) datasource. Datasource values are obtained from the given plottable
     * object.
     *
     * @param name      Source name.
     * @param plottable Plottable object.
     * @since 3.7
     */
    @Override
    public void datasource(String name, IPlottable plottable) {
        sources.add(new PDef(name, plottable));
    }

    /**
     * Creates a new 'fetched' datasource. Datasource values are obtained from the
     * given {@link org.rrd4j.core.FetchData} object.
     *
     * @param name      Source name.
     * @param fetchData FetchData object.
     */
    @Override
    public void datasource(String name, FetchData fetchData) {
        sources.add(new TDef(name, name, fetchData));
    }

    /**
     * Creates a new 'fetched' datasource. Datasource values are obtained from the
     * given {@link org.rrd4j.core.FetchData} object. 
     * Values will be extracted from the datasource dsName in the fetchData
     *
     * @param name      Source name.
     * @param dsName    Source name in fetchData.
     * @param fetchData FetchData object.
     */
    @Override
    public void datasource(String name, String dsName, FetchData fetchData) {
        sources.add(new TDef(name, dsName, fetchData));
    }

    /**
     * Create a new virtual datasource to get the 95th percentile value from another datasource
     *
     * @param name    Source name.
     * @param defName Other source name.
     * @deprecated Use {@link Variable} based method instead.
     */
    @Deprecated
    public void percentile(String name, String defName) {
        percentile(name, defName, DataProcessor.DEFAULT_PERCENTILE);
    }

    /**
     * Create a new virtual datasource to get a percentile value from another datasource
     *
     * @param name    Source name.
     * @param defName Other source name.
     * @param percent The percent value.
     * @deprecated Use {@link Variable} based method instead.
     */
    @Deprecated
    public void percentile(String name, String defName, double percent) {
        datasource(name, defName, new Variable.PERCENTILE(percent));
    }

    /**
     * <p>Calculates the chosen consolidation function CF over the given datasource
     * and creates the result by using the given format string.  In
     * the format string there should be a '%[l]f', '%[l]g' or '%[l]e' marker in
     * the place where the number should be printed.</p>
     * 
     * <p>If an additional '%s' is found AFTER the marker, the value will be
     * scaled and an appropriate SI magnitude unit will be printed in
     * place of the '%s' marker. The scaling will take the '--base' argument into consideration!</p>
     * 
     * <p>If a '%S' is used instead of a '%s', then instead of calculating
     * the appropriate SI magnitude unit for this value, the previously
     * calculated SI magnitude unit will be used.  This is useful if you
     * want all the values in a print statement to have the same SI magnitude unit.
     * If there was no previous SI magnitude calculation made,
     * then '%S' behaves like a '%s', unless the value is 0, in which case
     * it does not remember a SI magnitude unit and a SI magnitude unit
     * will only be calculated when the next '%s' is seen or the next '%S'
     * for a non-zero value.</p>
     * 
     * <p>Print results are collected in the {@link org.rrd4j.graph.RrdGraphInfo} object which is retrieved
     * from the {@link RrdGraph object} once the graph is created.</p>
     *
     * @param srcName   Virtual source name
     * @param consolFun Consolidation function to be applied to the source
     * @param format    Format string (like "average = %10.3f %s")
     * @deprecated Use {@link Variable} based method instead.
     */
    @Deprecated
    public void print(String srcName, ConsolFun consolFun, String format) {
        Variable var = consolFun.getVariable();
        String tempName = srcName + "_" + var.hashCode();
        datasource(tempName, srcName, var);
        comments.add(new PrintText(tempName, format, false, false));
    }

    /**
     * <p>Read the value of a variable (VDEF) and prints the value by using the given format string.  In
     * the format string there should be a '%[l]f', '%[l]g' or '%[l]e' marker in
     * the place where the number should be printed.</p>
     * 
     * <p>If an additional '%s' is found AFTER the marker, the value will be
     * scaled and an appropriate SI magnitude unit will be printed in
     * place of the '%s' marker. The scaling will take the '--base' argument into consideration!</p>
     * 
     * <p>If a '%S' is used instead of a '%s', then instead of calculating
     * the appropriate SI magnitude unit for this value, the previously
     * calculated SI magnitude unit will be used.  This is useful if you
     * want all the values in a print statement to have the same SI magnitude unit.
     * If there was no previous SI magnitude calculation made,
     * then '%S' behaves like a '%s', unless the value is 0, in which case
     * it does not remember a SI magnitude unit and a SI magnitude unit
     * will only be calculated when the next '%s' is seen or the next '%S'
     * for a non-zero value.</p>
     * 
     * <p>Print results are collected in the {@link org.rrd4j.graph.RrdGraphInfo} object which is retrieved
     * from the {@link RrdGraph object} once the graph is created.</p>
     *
     * @param srcName   Virtual source name
     * @param format    Format string (like "average = %10.3f %s")
     */
    public void print(String srcName, String format) {
        print(srcName, format, false);
    }

    /**
     * <p>Read the value of a variable (VDEF) and prints the the value or the time stamp, according to the strftime flag
     * by using the given format string.  In
     * and creates the result by using the given format string.  In
     * the format string there should be a '%[l]f', '%[l]g' or '%[l]e' marker in
     * the place where the number should be printed.</p>
     * 
     * <p>If an additional '%s' is found AFTER the marker, the value will be
     * scaled and an appropriate SI magnitude unit will be printed in
     * place of the '%s' marker. The scaling will take the '--base' argument into consideration!</p>
     * 
     * <p>If a '%S' is used instead of a '%s', then instead of calculating
     * the appropriate SI magnitude unit for this value, the previously
     * calculated SI magnitude unit will be used.  This is useful if you
     * want all the values in a print statement to have the same SI magnitude unit.
     * If there was no previous SI magnitude calculation made,
     * then '%S' behaves like a '%s', unless the value is 0, in which case
     * it does not remember a SI magnitude unit and a SI magnitude unit
     * will only be calculated when the next '%s' is seen or the next '%S'
     * for a non-zero value.</p>
     * 
     * <p>Print results are collected in the {@link org.rrd4j.graph.RrdGraphInfo} object which is retrieved
     * from the {@link RrdGraph object} once the graph is created.</p>
     *
     * @param srcName   Virtual source name
     * @param format    Format string (like "average = %10.3f %s")
     * @param strftime  use the timestamp from the variable (true) or the numerical value (false)
     */
    public void print(String srcName, String format, boolean strftime) {
        comments.add(new PrintText(srcName, format, false, strftime));
    }

    /**
     * This method does basically the same thing as {@link #print(String, ConsolFun, String)},
     * but the result is printed on the graph itself, below the chart area.
     *
     * @param srcName   Virtual source name.
     * @param consolFun Consolidation function to be applied to the source.
     * @param format    Format string (like "average = %10.3f %s")
     * @deprecated Use {@link Variable} based method instead.
     */
    @Deprecated
    public void gprint(String srcName, ConsolFun consolFun, String format) {
        Variable var = consolFun.getVariable();
        String tempName = srcName + "_" + var.hashCode();
        this.datasource(tempName, srcName, var);
        comments.add(new PrintText(tempName, format, true, false));
    }

    /**
     * <p>Read the value of a variable (VDEF) and prints the value by using the given format string.  In
     * the format string there should be a '%[l]f', '%[l]g' or '%[l]e' marker in
     * the place where the number should be printed.</p>
     * 
     * <p>If an additional '%s' is found AFTER the marker, the value will be
     * scaled and an appropriate SI magnitude unit will be printed in
     * place of the '%s' marker. The scaling will take the '--base' argument into consideration!</p>
     * 
     * <p>If a '%S' is used instead of a '%s', then instead of calculating
     * the appropriate SI magnitude unit for this value, the previously
     * calculated SI magnitude unit will be used.  This is useful if you
     * want all the values in a print statement to have the same SI magnitude unit.
     * If there was no previous SI magnitude calculation made,
     * then '%S' behaves like a '%s', unless the value is 0, in which case
     * it does not remember a SI magnitude unit and a SI magnitude unit
     * will only be calculated when the next '%s' is seen or the next '%S'
     * for a non-zero value.</p>
     * 
     * print results are added to the graph as a legend
     *
     * @param srcName   Virtual source name
     * @param format    Format string (like "average = %10.3f %s")
     */
    public void gprint(String srcName, String format) {
        gprint(srcName, format, false);
    }

    /**
     * <p>Read the value of a variable (VDEF) and prints the the value or the time stamp, according to the strftime flag
     * by using the given format string.  In
     * and creates the result by using the given format string.  In
     * the format string there should be a '%[l]f', '%[l]g' or '%[l]e' marker in
     * the place where the number should be printed.</p>
     * 
     * <p>If an additional '%s' is found AFTER the marker, the value will be
     * scaled and an appropriate SI magnitude unit will be printed in
     * place of the '%s' marker. The scaling will take the '--base' argument into consideration!</p>
     * 
     * <p>If a '%S' is used instead of a '%s', then instead of calculating
     * the appropriate SI magnitude unit for this value, the previously
     * calculated SI magnitude unit will be used.  This is useful if you
     * want all the values in a print statement to have the same SI magnitude unit.
     * If there was no previous SI magnitude calculation made,
     * then '%S' behaves like a '%s', unless the value is 0, in which case
     * it does not remember a SI magnitude unit and a SI magnitude unit
     * will only be calculated when the next '%s' is seen or the next '%S'
     * for a non-zero value.</p>
     * 
     * <p>print results are added to the graph as a legend.</p>
     *
     * @param srcName   Virtual source name
     * @param format    Format string (like "average = %10.3f %s")
     * @param strftime  use the timestamp from the variable (true) or the numerical value (false)
     */
    public void gprint(String srcName, String format, boolean strftime) {
        comments.add(new PrintText(srcName, format, true, strftime));
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
     * Draws a horizontal rule into the graph.
     *
     * @param value Position of the rule
     * @param color Rule color
     */
    public void hrule(double value, Paint color) {
        hrule(value, color, null, 1.0F);
    }

    /**
     * Draws a horizontal rule into the graph and optionally adds a legend.
     *
     * @param value  Position of the rule
     * @param color  Rule color
     * @param legend Legend text. If null, legend text will be omitted.
     */
    public void hrule(double value, Paint color, String legend) {
        hrule(value, color, legend, 1.0F);
    }

    /**
     * Draws a horizontal rule into the graph and optionally adds a legend.
     *
     * @param value  Position of the rule
     * @param color  Rule color
     * @param legend Legend text. If null, legend text will be omitted.
     * @param width  Rule width
     */
    public void hrule(double value, Paint color, String legend, float width) {
        hrule(value, color, legend, new BasicStroke(width));
    }

    /**
     * Draws a horizontal rule into the graph and optionally adds a legend.
     *
     * @param value  Position of the rule
     * @param color  Rule color
     * @param legend Legend text. If null, legend text will be omitted.
     * @param stroke Rule stroke
     */
    public void hrule(double value, Paint color, String legend, BasicStroke stroke) {
        LegendText legendText = new LegendText(color, legend);
        comments.add(legendText);
        plotElements.add(new HRule(value, color, legendText, stroke));
    }

    /**
     * Draws a vertical rule into the graph.
     *
     * @param timestamp Position of the rule (seconds since epoch)
     * @param color     Rule color
     */
    public void vrule(long timestamp, Paint color) {
        vrule(timestamp, color, null, 1.0F);
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
        vrule(timestamp, color, legend, new BasicStroke(width));
    }

    /**
     * Draws a vertical rule into the graph and optionally adds a legend
     *
     * @param timestamp Position of the rule (seconds since epoch)
     * @param color     Rule color
     * @param legend    Legend text. Use null to omit the text.
     * @param stroke    Rule stroke
     */
    public void vrule(long timestamp, Paint color, String legend, BasicStroke stroke) {
        LegendText legendText = new LegendText(color, legend);
        comments.add(legendText);
        plotElements.add(new VRule(timestamp, color, legendText, stroke));
    }

    /**
     * Draws a horizontal span into the graph.
     *
     * @param start Starting value of the span
     * @param end   Ending value of the span
     * @param color Rule color
     */
    public void hspan(double start, double end, Paint color) {
        hspan(start, end, color, null);
    }

    /**
     * Draws a horizontal span into the graph and optionally adds a legend.
     *
     * @param start     Starting value of the span
     * @param end       Ending value of the span
     * @param color     Rule color
     * @param legend    Legend text. Use null to omit the text.
     */
    public void hspan(double start, double end, Paint color, String legend) {
        LegendText legendText = new LegendText(color, legend);
        comments.add(legendText);
        plotElements.add(new HSpan(start, end, color, legendText));
    }

    /**
     * Draws a vertical span into the graph.
     *
     * @param start     Start time for the span (seconds since epoch)
     * @param end       End time for the span (seconds since epoch)
     * @param color     Rule color
     */
    public void vspan(long start, long end, Paint color) {
        vspan(start, end, color, null);
    }

    /**
     * Draws a vertical span into the graph and optionally adds a legend.
     *
     * @param start     Start time for the span (seconds since epoch)
     * @param end       End time for the span (seconds since epoch)
     * @param color     Rule color
     * @param legend    Legend text. Use null to omit the text.
     */
    public void vspan(long start, long end, Paint color, String legend) {
        LegendText legendText = new LegendText(color, legend);
        comments.add(legendText);
        plotElements.add(new VSpan(start, end, color, legendText));
    }

    /**
     * Plots requested data as a line, using the color specified. Line width is assumed to be
     * 1.0F.
     *
     * @param srcName Virtual source name
     * @param color   Line color
     */
    public void line(String srcName, Paint color) {
        line(srcName, color, null, 1F, false);
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
        line(srcName, color, legend, 1F, false);
    }


    /**
     * Plots requested data as a line, using the color and the line width specified.
     *
     * @param srcName Virtual source name
     * @param color   Line color
     * @param width   Line width (default: 1.0F)
     */
    public void line(String srcName, Paint color, float width) {
        line(srcName, color, null, width, false);
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
        line(srcName, color, legend, width, false);
    }

    /**
     * Plots requested data as a line, using the color and the line width specified.
     *
     * @param srcName Virtual source name
     * @param color   Line color.
     * @param legend  Legend text.
     * @param width   Line width (default: 1.0F).
     * @param stack   true if it will be stacked.
     */
    public void line(String srcName, Paint color, String legend, float width, boolean stack) {
        if (legend != null) {
            comments.add(new LegendText(color, legend));
        }
        SourcedPlotElement parent = stack ? findParent() : null;
        plotElements.add(new Line(srcName, color, new BasicStroke(width), parent));
    }

    /**
     * Plots requested data as a line, using the color and the {@link java.awt.BasicStroke} specified.
     *
     * @param srcName Virtual source name
     * @param color   Line color.
     * @param legend  Legend text.
     * @param stroke  Line stroke to use.
     * @param stack   true if it will be stacked.
     */
    public void line(String srcName, Paint color, String legend, BasicStroke stroke, boolean stack) {
        if (legend != null) {
            comments.add(new LegendText(color, legend));
        }
        SourcedPlotElement parent = stack ? findParent() : null;
        plotElements.add(new Line(srcName, color, stroke, parent));
    }

    /**
     * Define a line like any other but with constant value, it can be stacked
     * @param value Line position.
     * @param color Line color.
     * @param width Line width (default: 1.0F).
     * @param stack true if it will be stacked.
     */
    public void line(double value, Paint color, float width, boolean stack) {
        SourcedPlotElement parent = stack ? findParent() : null;
        plotElements.add(new ConstantLine(value, color, new BasicStroke(width), parent));
    }

    /**
     * Define a line like any other but with constant value, it can be stacked
     * @param value  Line position.
     * @param color  Line color.
     * @param stroke Line stroke to use.
     * @param stack  true if it will be stacked.
     */
    public void line(double value, Paint color, BasicStroke stroke, boolean stack) {
        SourcedPlotElement parent = stack ? findParent() : null;
        plotElements.add(new ConstantLine(value, color, stroke, parent));
    }

    /**
     * Plots requested data in the form of the filled area starting from zero, using
     * the color specified.
     *
     * @param srcName Virtual source name.
     * @param color   Color of the filled area.
     */
    public void area(String srcName, Paint color) {
        area(srcName, color, null, false);
    }

    /**
     * Plots requested data in the form of the filled area starting from zero, using
     * the color specified.
     *
     * @param srcName Virtual source name.
     * @param color   Color of the filled area.
     * @param legend  Legend text.
     */
    public void area(String srcName, Paint color, String legend) {
        area(srcName, color, legend, false);
    }

    /**
     * Plots requested data in the form of the filled area starting from zero, using
     * the color specified.
     *
     * @param srcName Virtual source name.
     * @param color   Color of the filled area.
     * @param legend  Legend text.
     * @param stack   true if it will be stacked.
     */
    public void area(String srcName, Paint color, String legend, boolean stack) {
        if (legend != null) {
            comments.add(new LegendText(color, legend));
        }
        SourcedPlotElement parent = stack ? findParent() : null;
        plotElements.add(new Area(srcName, color, parent));
    }

    /**
     * Add a area like any other but with a constant value, it can be stacked like any other area
     * @param value Area position
     * @param color Color of the filled area.
     * @param stack true if it will be stacked.
     */
    public void area(double value, Paint color, boolean stack) {
        SourcedPlotElement parent = stack ? findParent() : null;
        plotElements.add(new ConstantArea(value, color, parent));
    }

    /**
     * <p>Does the same as {@link #line(String, java.awt.Paint)},
     * but the graph gets stacked on top of the
     * previous LINE, AREA or STACK graph. Depending on the type of the
     * previous graph, the STACK will be either a LINE or an AREA.  This
     * obviously implies that the first STACK must be preceded by an AREA
     * or LINE.</p>
     * 
     * <p>Note, that when you STACK onto *UNKNOWN* data, Rrd4j will not
     * draw any graphics ... *UNKNOWN* is not zero.</p>
     *
     * @param srcName Virtual source name
     * @param color   Stacked graph color
     * @throws java.lang.IllegalArgumentException Thrown if this STACK has no previously defined AREA, STACK or LINE
     *                                  graph bellow it.
     */
    public void stack(String srcName, Paint color) {
        stack(srcName, color, null);
    }

    /**
     * <p>Does the same as {@link #line(String, java.awt.Paint, String)},
     * but the graph gets stacked on top of the
     * previous LINE, AREA or STACK graph. Depending on the type of the
     * previous graph, the STACK will be either a LINE or an AREA.  This
     * obviously implies that the first STACK must be preceded by an AREA
     * or LINE.</p>
     * 
     * Note, that when you STACK onto *UNKNOWN* data, Rrd4j will not
     * draw any graphics ... *UNKNOWN* is not zero.
     *
     * @param srcName Virtual source name
     * @param color   Stacked graph color
     * @param legend  Legend text
     * @throws java.lang.IllegalArgumentException Thrown if this STACK has no previously defined AREA, STACK or LINE
     *                                  graph bellow it.
     */
    public void stack(String srcName, Paint color, String legend) {
        SourcedPlotElement parent = findParent();
        if (legend != null) {
            comments.add(new LegendText(color, legend));
        }
        plotElements.add(new Stack(parent, srcName, color));
    }

    private SourcedPlotElement findParent() {
        // find parent AREA or LINE
        SourcedPlotElement parent = null;
        for (int i = plotElements.size() - 1; i >= 0; i--) {
            PlotElement plotElement = plotElements.get(i);
            if (plotElement instanceof SourcedPlotElement) {
                parent = (SourcedPlotElement) plotElement;
                break;
            }
        }
        if (parent == null) 
            throw new IllegalArgumentException("You have to stack graph onto something (line or area)");
        return parent;
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
     * Controls if the text should be antialiased or not.
     *
     * @param textAntiAliasing use true to turn text-antialiasing on, false to turn it off (default)
     */
    public void setTextAntiAliasing(boolean textAntiAliasing) {
        this.textAntiAliasing = textAntiAliasing;
    }

    /**
     * Sets the signature string that runs along the right-side of the graph.
     * Defaults to "Generated by RRD4J".
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
     *                       {@link org.rrd4j.graph.RrdGraphConstants#MONDAY MONDAY},
     *                       {@link org.rrd4j.graph.RrdGraphConstants#TUESDAY TUESDAY},
     *                       {@link org.rrd4j.graph.RrdGraphConstants#WEDNESDAY WEDNESDAY},
     *                       {@link org.rrd4j.graph.RrdGraphConstants#THURSDAY THURSDAY},
     *                       {@link org.rrd4j.graph.RrdGraphConstants#FRIDAY FRIDAY},
     *                       {@link org.rrd4j.graph.RrdGraphConstants#SATURDAY SATURDAY},
     *                       {@link org.rrd4j.graph.RrdGraphConstants#SUNDAY SUNDAY}
     */
    public void setFirstDayOfWeek(int firstDayOfWeek) {
        this.firstDayOfWeek = firstDayOfWeek;
    }

    /**
     * Set the locale used for the legend.
     * <p>
     *
     * It overides the firstDayOfWeek
     *
     * @param locale the locale to set
     */
    public void setLocale(Locale locale) {
        this.locale = locale;
        this.firstDayOfWeek = Calendar.getInstance(Locale.getDefault()).getFirstDayOfWeek();
    }

    /**
     * Set the time zone used for the legend.
     *
     * @param tz the time zone to set
     */
    @Override
    public void setTimeZone(TimeZone tz) {
        this.tz = tz;
    }

    /**
     * @since 3.7
     */
    @Override
    public TimeZone getTimeZone() {
        return this.tz;
    }

    /**
     * Set the Stroke used to draw grid
     *
     * @param gridStroke a {@link java.awt.Stroke} object.
     */
    public void setGridStroke(Stroke gridStroke) {
        this.gridStroke = gridStroke;
    }

    /**
     * Set the stroke used to draw ticks
     *
     * @param tickStroke a {@link java.awt.Stroke} object.
     */
    public void setTickStroke(Stroke tickStroke) {
        this.tickStroke = tickStroke;
    }

    /**
     * Allows to set a downsampler, used to improved the visual representation of graph.
     * <p>
     * More details can be found on <a href="http://skemman.is/en/item/view/1946/15343">Sveinn Steinarsson's thesis</a>
     * 
     * @param downsampler The downsampler that will be used
     */
    public void setDownsampler(DownSampler downsampler) {
        this.downsampler = downsampler;
    }

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

    Paint getColor(ElementsNames element) {
        return colors[element.ordinal()];
    }

    /**
     * @since 3.7
     */
    @Override
    public long getEndTime() {
        return this.endTime;
    }

    /**
     * @since 3.7
     */
    @Override
    public long getStartTime() {
        return this.startTime;
    }

    /**
     * @since 3.7
     */
    @Override
    public long getStep() {
        return this.step;
    }

    /**
     * @since 3.10
     */
    boolean drawTicks() {
        return tickStroke != null && ((! (tickStroke instanceof BasicStroke)) || ((BasicStroke)tickStroke).getLineWidth() > 0);
    }

}
