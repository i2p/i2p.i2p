package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Stroke;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/**
 * Class to represent various constants used for graphing. No methods are specified.
 * <p>
 * The fonts settings can be changed use some on the following properties, sorted by increased priority.
 * <ol>
 * <li><code>org.rrd4j.fonts.properties</code></li>
 * <li><code>org.rrd4j.fonts.properties.url</code></li>
 * <li><code>org.rrd4j.font.plain</code></li>
 * <li><code>org.rrd4j.font.bold</code></li>
 * <li><code>org.rrd4j.font.plain.url</code></li>
 * <li><code>org.rrd4j.font.bold.url</code></li>
 * </ol>
 * 
 * If either <code>org.rrd4j.fonts.properties</code> or <code>org.rrd4j.fonts.properties.url</code> is used, the file provided contains any other property of lower priority .
 * The last four properties defines directly the plain or bold font. All properties URL related (<code>org.rrd4j.fonts.url</code>, <code>org.rrd4j.font.plain.url</code> and
 * <code>org.rrd4j.font.bold.url</code>) download data from an URL. They are useful when those data are provided by the file system, defined by the OS. 
 * The others search for the data in the classpath. So it's easy to provided font-pack as a jar that's put before RRD44J's jar.
 * <p>
 * The default settings uses <code>org.rrd4j.fonts.properties</code> looking for the file <code>/rrd4jfonts.properties</code> in the classpath.
 */
public interface RrdGraphConstants {
    /**
     * Default graph starting time
     */
    String DEFAULT_START = "end-1d";
    /**
     * Default graph ending time
     */
    String DEFAULT_END = "now";

    /**
     * HH:mm time format
     */
    String HH_MM = "HH:mm";

    /**
     * Constant to represent second
     */
    int SECOND = Calendar.SECOND;
    /**
     * Constant to represent minute
     */
    int MINUTE = Calendar.MINUTE;
    /**
     * Constant to represent hour
     */
    int HOUR = Calendar.HOUR_OF_DAY;
    /**
     * Constant to represent day
     */
    int DAY = Calendar.DAY_OF_MONTH;
    /**
     * Constant to represent week
     */
    int WEEK = Calendar.WEEK_OF_YEAR;
    /**
     * Constant to represent month
     */
    int MONTH = Calendar.MONTH;
    /**
     * Constant to represent year
     */
    int YEAR = Calendar.YEAR;

    /**
     * Constant to represent Monday
     */
    int MONDAY = Calendar.MONDAY;
    /**
     * Constant to represent Tuesday
     */
    int TUESDAY = Calendar.TUESDAY;
    /**
     * Constant to represent Wednesday
     */
    int WEDNESDAY = Calendar.WEDNESDAY;
    /**
     * Constant to represent Thursday
     */
    int THURSDAY = Calendar.THURSDAY;
    /**
     * Constant to represent Friday
     */
    int FRIDAY = Calendar.FRIDAY;
    /**
     * Constant to represent Saturday
     */
    int SATURDAY = Calendar.SATURDAY;
    /**
     * Constant to represent Sunday
     */
    int SUNDAY = Calendar.SUNDAY;

    /**
     * Index of the canvas color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
     */
    @Deprecated
    int COLOR_CANVAS = 0;
    /**
     * Index of the background color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
     */
    @Deprecated
    int COLOR_BACK = 1;
    /**
     * Index of the top-left graph shade color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
     */
    @Deprecated
    int COLOR_SHADEA = 2;
    /**
     * Index of the bottom-right graph shade color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
     */
    @Deprecated
    int COLOR_SHADEB = 3;
    /**
     * Index of the minor grid color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
     */
    @Deprecated
    int COLOR_GRID = 4;
    /**
     * Index of the major grid color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
     */
    @Deprecated
    int COLOR_MGRID = 5;
    /**
     * Index of the font color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
     */
    @Deprecated
    int COLOR_FONT = 6;
    /**
     * Index of the frame color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
     */
    @Deprecated
    int COLOR_FRAME = 7;
    /**
     * Index of the arrow color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
     */
    @Deprecated
    int COLOR_ARROW = 8;
    /**
     * Index of the x-axis color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
     */
    @Deprecated
    int COLOR_XAXIS = 9;
    /**
     * Index of the yaxis color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
     */
    @Deprecated
    int COLOR_YAXIS = 10;

    /**
     * Default first day of the week (obtained from the default locale)
     */
    int FIRST_DAY_OF_WEEK = Calendar.getInstance(Locale.getDefault()).getFirstDayOfWeek();

    /**
     * Default graph canvas color
     */
    Color DEFAULT_CANVAS_COLOR = Color.WHITE;
    /**
     * Default graph background color
     */
    Color DEFAULT_BACK_COLOR = new Color(245, 245, 245);
    /**
     * Default top-left graph shade color
     */
    Color DEFAULT_SHADEA_COLOR = new Color(200, 200, 200);
    /**
     * Default bottom-right graph shade color
     */
    Color DEFAULT_SHADEB_COLOR = new Color(150, 150, 150);
    /**
     * Default minor grid color
     */
    Color DEFAULT_GRID_COLOR = new Color(171, 171, 171, 95);
    /**
     * Default major grid color
     */
    Color DEFAULT_MGRID_COLOR = new Color(255, 91, 91, 95);
    /**
     * Default font color
     */
    Color DEFAULT_FONT_COLOR = Color.BLACK;
    /**
     * Default frame color
     */
    Color DEFAULT_FRAME_COLOR = Color.BLACK;
    /**
     * Default arrow color
     */
    Color DEFAULT_ARROW_COLOR = new Color(128, 31, 31);
    /**
     * Default x-axis color
     */
    Color DEFAULT_XAXIS_COLOR = Color.BLACK;
    /**
     * Default x-axis color
     */
    Color DEFAULT_YAXIS_COLOR = Color.BLACK;

    /**
     * An transparent color
     */
    Color BLIND_COLOR = new Color(0, 0, 0, 0);

    /**
     * Constant to represent left alignment marker
     */
    @Deprecated
    String ALIGN_LEFT_MARKER = Markers.ALIGN_LEFT_MARKER.marker;
    /**
     * Constant to represent left alignment marker, without new line
     */
    @Deprecated
    String ALIGN_LEFTNONL_MARKER = Markers.ALIGN_LEFTNONL_MARKER.marker;
    /**
     * Constant to represent centered alignment marker
     */
    @Deprecated
    String ALIGN_CENTER_MARKER = Markers.ALIGN_CENTER_MARKER.marker;
    /**
     * Constant to represent right alignment marker
     */
    @Deprecated
    String ALIGN_RIGHT_MARKER = Markers.ALIGN_RIGHT_MARKER.marker;
    /**
     * Constant to represent justified alignment marker
     */
    @Deprecated
    String ALIGN_JUSTIFIED_MARKER = Markers.ALIGN_JUSTIFIED_MARKER.marker;
    /**
     * Constant to represent "glue" marker
     */
    @Deprecated
    String GLUE_MARKER = Markers.GLUE_MARKER.marker;
    /**
     * Constant to represent vertical spacing marker
     */
    @Deprecated
    String VERTICAL_SPACING_MARKER = Markers.VERTICAL_SPACING_MARKER.marker;
    /**
     * Constant to represent no justification markers
     */
    @Deprecated
    String NO_JUSTIFICATION_MARKER = Markers.NO_JUSTIFICATION_MARKER.marker;

    /**
     * Constant to represent in-memory image name
     */
    String IN_MEMORY_IMAGE = "-";

    /**
     * Default units length
     */
    int DEFAULT_UNITS_LENGTH = 9;
    /**
     * Default graph width
     */
    int DEFAULT_WIDTH = 400;
    /**
     * Default graph height
     */
    int DEFAULT_HEIGHT = 100;
    /**
     * Default image format
     */
    String DEFAULT_IMAGE_FORMAT = "gif";
    /**
     * Default image quality, used only for jpeg graphs
     */
    float DEFAULT_IMAGE_QUALITY = 0.8F; // only for jpegs, not used for png/gif
    /**
     * Default value base
     */
    double DEFAULT_BASE = 1000;

    /**
     * The file that contains font configuration searched in the class path. The default value is <code>/rrd4jfonts.properties</code>
     */
    public static final String PROPERTYFONTSPROPERTIES = "org.rrd4j.fonts.properties";
    /**
     * A possible URL to a configuration file.
     */
    public static final String PROPERTYFONTSURL = "org.rrd4j.fonts.properties.url";
    /**
     * The name of the plain font, used to define the {@link #DEFAULT_SMALL_FONT} and the {@link GATOR_FONT}. To be found in the classpath.
     */
    public static final String PROPERTYFONTPLAIN = "org.rrd4j.font.plain";
    /**
     * The name of the bold font, used to define the {@link #DEFAULT_LARGE_FONT}. To be found in the classpath.
     */
    public static final String PROPERTYFONTBOLD = "org.rrd4j.font.bold";
    /**
     * An URL to the plain font, used to define the {@link #DEFAULT_SMALL_FONT} and the {@link GATOR_FONT}.
     */
    public static final String PROPERTYFONTPLAINURL = "org.rrd4j.font.plain.url";
    /**
     * An URL to the bold font, used to define the {@link #DEFAULT_LARGE_FONT}.
     */
    public static final String PROPERTYFONTBOLDURL = "org.rrd4j.font.bold.url";

    /**
     * Font constructor, to use embedded fonts. Not really useful outside internal use for RRD4J.
     */
    static class FontConstructor {
        private static final Properties fileProps = new Properties();
        static {
            refreshConf();
        }
        private FontConstructor() {}
        
        /**
         * Used for tests
         */
        static void refreshConf() {
            fileProps.clear();
            Optional.ofNullable(System.getProperty(PROPERTYFONTSPROPERTIES, "/rrd4jfonts.properties"))
                    .filter(s -> ! s.isEmpty())
                    .map(RrdGraphConstants.class::getResourceAsStream)
                    .ifPresent(t -> {
                        try {
                            fileProps.load(t);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            Optional.ofNullable(System.getProperty(PROPERTYFONTSURL))
                    .filter(s -> ! s.isEmpty())
                    .ifPresent(t -> {
                        try {
                            fileProps.load(new URL(t).openStream());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            for (String prop: new String[] {PROPERTYFONTPLAIN, PROPERTYFONTBOLD, PROPERTYFONTPLAINURL, PROPERTYFONTBOLDURL}) {
                Optional.ofNullable(System.getProperty(prop))
                        .filter(s -> ! s.isEmpty())
                        .ifPresent(s -> fileProps.put(prop, s));
            }
        }

        /**
         * Return the default RRD4J's default font for the given strength
         * @param type {@link java.awt.Font#BOLD} for a bold fond, any other value return plain style.
         * @param size the size for the new Font
         * @return a new {@link java.awt.Font} instance
         */
        public static Font getFont(int type, int size) {
/*
            Function<String, InputStream> fontStream = null;
            String fontPath = fileProps.getProperty(type == Font.BOLD ? PROPERTYFONTBOLDURL : PROPERTYFONTPLAINURL);
            if (fontPath!= null) {
                fontStream = s -> {
                    try {
                        return new URL(s).openStream();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                };
            } else {
                fontPath = fileProps.getProperty(type == Font.BOLD ? PROPERTYFONTBOLD : PROPERTYFONTPLAIN);
                fontStream = RrdGraphConstants.class::getResourceAsStream;
            }
            try (InputStream fontstream = fontStream.apply(fontPath)) {
                return Font.createFont(Font.TRUETYPE_FONT, fontstream).deriveFont((float)size);
            } catch (FontFormatException | IOException e) {
                throw new RuntimeException(e);
            }
*/
            return new Font("Monospaced", type, size);
        }
    }

    /**
     * Default graph small font
     */
    static final Font DEFAULT_SMALL_FONT = FontConstructor.getFont(Font.PLAIN, 10);
    /**
     * Default graph large font
     */
    static final Font DEFAULT_LARGE_FONT = FontConstructor.getFont(Font.BOLD, 12);
    /**
     * Font for the Gator
     */
    static final Font GATOR_FONT = FontConstructor.getFont(Font.PLAIN, 9);
    /**
     * Used internally
     */
    double LEGEND_LEADING = 1.2; // chars
    /**
     * Used internally
     */
    double LEGEND_LEADING_SMALL = 0.7; // chars
    /**
     * Used internally
     */
    double LEGEND_BOX_SPACE = 1.2; // chars
    /**
     * Used internally
     */
    double LEGEND_BOX = 0.9; // chars
    /**
     * Used internally
     */
    int LEGEND_INTERSPACING = 2; // chars
    /**
     * Used internally
     */
    int PADDING_LEFT = 10; // pix
    /**
     * Used internally
     */
    int PADDING_TOP = 12; // pix
    /**
     * Used internally
     */
    int PADDING_TITLE = 6; // pix
    /**
     * Used internally
     */
    int PADDING_RIGHT = 16; // pix
    /**
     * Used internally
     */
    int PADDING_PLOT = 2; //chars
    /**
     * Used internally
     */
    int PADDING_LEGEND = 2; // chars
    /**
     * Used internally
     */
    int PADDING_BOTTOM = 6; // pix
    /**
     * Used internally
     */
    int PADDING_VLABEL = 7; // pix

    /**
     * Stroke used to draw grid
     */
    Stroke GRID_STROKE = new BasicStroke(1);

    /**
     * Stroke used to draw ticks
     */
    Stroke TICK_STROKE = new BasicStroke(1);

    /**
     * Allowed font tag names which can be used in {@link org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag, java.awt.Font)} method
     */
    public enum FontTag  {
        /**
         * Index of the default font. Used in {@link org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag, java.awt.Font)}
         */
        DEFAULT,
        /**
         * Index of the title font. Used in {@link org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag, java.awt.Font)}
         */
        TITLE,
        /**
         * Index of the axis label font. Used in {@link org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag, java.awt.Font)}
         */
        AXIS,
        /**
         * Index of the vertical unit label font. Used in {@link org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag, java.awt.Font)}
         */
        UNIT,
        /**
         * Index of the graph legend font. Used in {@link org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag, java.awt.Font)}
         */
        LEGEND,
        /**
         * Index of the edge watermark font. Used in {@link org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag, java.awt.Font)}
         */
        WATERMARK;

        public void set(Font f, Font[] fonts) {
            fonts[this.ordinal()] = f;
        }

        public Font get(Font f, Font[] fonts) {
            return fonts[this.ordinal()];
        }

    }

    FontTag FONTTAG_DEFAULT   = FontTag.DEFAULT;

    FontTag FONTTAG_TITLE     = FontTag.TITLE;

    FontTag FONTTAG_AXIS      = FontTag.AXIS;

    FontTag FONTTAG_UNIT      = FontTag.AXIS;

    FontTag FONTTAG_LEGEND    = FontTag.LEGEND;

    FontTag FONTTAG_WATERMARK = FontTag.WATERMARK;

}
