package net.i2p.router.web;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
import static net.i2p.router.web.GraphConstants.*;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

import org.rrd4j.ConsolFun;
import org.rrd4j.core.RrdException;
import org.rrd4j.data.Variable;
import org.rrd4j.graph.ElementsNames;
import org.rrd4j.graph.RrdGraph;
import org.rrd4j.graph.RrdGraphDef;

/**
 *  Generate the RRD graph png images,
 *  including the combined rate graph.
 *
 *  @since 0.6.1.13
 */
class SummaryRenderer {
    private final Log _log;
    private final SummaryListener _listener;
    private final I2PAppContext _context;
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);
    // light theme
    private static final Color BACK_COLOR = new Color(246, 246, 255);
    private static final Color SHADEA_COLOR = BACK_COLOR;
    private static final Color SHADEB_COLOR = BACK_COLOR;
    private static final Color GRID_COLOR = new Color(100, 100, 100, 75);
    private static final Color MGRID_COLOR = new Color(255, 91, 91, 110);
    private static final Color FONT_COLOR = new Color(51, 51, 63);
    private static final Color FRAME_COLOR = FONT_COLOR;
    private static final Color AREA_COLOR = new Color(100, 160, 200, 200);
    private static final Color LINE_COLOR = new Color(0, 30, 110, 255);
    private static final Color RESTART_BAR_COLOR = new Color(223, 13, 13, 255);
    // dark theme adapted from I2P+
    private static final Color BACK_COLOR_DARK = new Color(16, 16, 16);
    private static final Color SHADEA_COLOR_DARK = BACK_COLOR_DARK;
    private static final Color SHADEB_COLOR_DARK = BACK_COLOR_DARK;
    private static final Color GRID_COLOR_DARK = new Color(244, 244, 190, 50);
    private static final Color MGRID_COLOR_DARK = new Color(200, 200, 0, 50);
    private static final Color FONT_COLOR_DARK = new Color(244, 244, 190);
    private static final Color FRAME_COLOR_DARK = TRANSPARENT;
    private static final Color AREA_COLOR_DARK = new Color(0, 72, 8, 220);
    private static final Color LINE_COLOR_DARK = new Color(100, 200, 160);
    private static final Color RESTART_BAR_COLOR_DARK = new Color(200, 16, 48, 220);
    private static final Color AXIS_COLOR_DARK = new Color(244, 244, 190, 200);
    private static final Color CANVAS_COLOR_DARK = new Color(20, 20, 20);

    // hide the arrow, full transparent
    private static final Color ARROW_COLOR = TRANSPARENT;
    private static final boolean IS_WIN = SystemVersion.isWindows();
    private static final String DEFAULT_FONT_NAME = IS_WIN ?
            "Lucida Console" : "Monospaced";
    private static final String DEFAULT_TITLE_FONT_NAME = "Dialog";
    private static final String DEFAULT_LEGEND_FONT_NAME = "Dialog";
    private static final String PROP_FONT_MONO = "routerconsole.graphFont.unit";
    private static final String PROP_FONT_LEGEND = "routerconsole.graphFont.legend";
    private static final String PROP_FONT_TITLE = "routerconsole.graphFont.title";
    private static final int SIZE_MONO = 10;
    private static final int SIZE_LEGEND = 10;
    private static final int SIZE_TITLE = 13;
    private static final long[] RATES = new long[] { 60*60*1000 };
    // dotted line
    private static final Stroke GRID_STROKE = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[] {1, 1}, 0);
    // hide grid
    private static final Stroke TICK_STROKE = new BasicStroke(0);

    public SummaryRenderer(I2PAppContext ctx, SummaryListener lsnr) { 
        _log = ctx.logManager().getLog(SummaryRenderer.class);
        _listener = lsnr;
        _context = ctx;
        ctx.statManager().createRateStat("graph.renderTime", "", "Router", RATES);
    }
    
    /**
     * Render the stats as determined by the specified JRobin xml config,
     * but note that this doesn't work on stock jvms, as it requires 
     * DOM level 3 load and store support.  Perhaps we can bundle that, or
     * specify who can get it from where, etc.
     *
     * @deprecated unused
     * @throws UnsupportedOperationException always
     */
    @Deprecated
    public static synchronized void render(I2PAppContext ctx, OutputStream out, String filename) throws IOException {
        throw new UnsupportedOperationException();
    }

    public void render(OutputStream out) throws IOException { render(out, DEFAULT_X, DEFAULT_Y,
                                                                     false, false, false, false, -1, 0, false); }

    /**
     *  Single graph.
     *
     *  @param endp number of periods before now
     */
    public void render(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid,
                       boolean hideTitle, boolean showEvents, int periodCount,
                       int endp, boolean showCredit) throws IOException {
        render(out, width, height, hideLegend, hideGrid, hideTitle,
               showEvents, periodCount, endp, showCredit, null, null);
    }

    /**
     *  Single or two-data-source graph.
     *
     *  @param lsnr2 2nd data source to plot on same graph, or null. Not recommended for events.
     *  @param titleOverride If non-null, overrides the title
     *  @since 0.9.6 consolidated from StatSummarizer for bw.combined
     */
    public void render(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid,
                       boolean hideTitle, boolean showEvents, int periodCount,
                       int endp, boolean showCredit, SummaryListener lsnr2, String titleOverride) throws IOException {
        long begin = System.currentTimeMillis();
        // prevent NaNs if we are skewed ahead of system time
        long end = Math.min(_listener.now(), begin - 75*1000);
        long period = _listener.getRate().getPeriod();
        if (endp > 0)
            end -= period * endp;
        if (periodCount <= 0 || periodCount > _listener.getRows())
            periodCount = _listener.getRows();
        long start = end - (period * periodCount);
        ImageOutputStream ios = null;
        try {
            RrdGraphDef def = new RrdGraphDef(start/1000, end/1000);

            // Override defaults
            boolean isDark = !_context.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME).equals(CSSHelper.DEFAULT_THEME);
            if (isDark) {
                def.setColor(ElementsNames.back,   BACK_COLOR_DARK);
                def.setColor(ElementsNames.shadea, SHADEA_COLOR_DARK);
                def.setColor(ElementsNames.shadeb, SHADEB_COLOR_DARK);
                def.setColor(ElementsNames.grid,   GRID_COLOR_DARK);
                def.setColor(ElementsNames.mgrid,  MGRID_COLOR_DARK);
                def.setColor(ElementsNames.font,   FONT_COLOR_DARK);
                def.setColor(ElementsNames.frame,  FRAME_COLOR_DARK);
                def.setColor(ElementsNames.xaxis,  AXIS_COLOR_DARK);
                def.setColor(ElementsNames.yaxis,  AXIS_COLOR_DARK);
                def.setColor(ElementsNames.canvas, CANVAS_COLOR_DARK);
            } else {
                def.setColor(ElementsNames.back,   BACK_COLOR);
                def.setColor(ElementsNames.shadea, SHADEA_COLOR);
                def.setColor(ElementsNames.shadeb, SHADEB_COLOR);
                def.setColor(ElementsNames.grid,   GRID_COLOR);
                def.setColor(ElementsNames.mgrid,  MGRID_COLOR);
                def.setColor(ElementsNames.font,   FONT_COLOR);
                def.setColor(ElementsNames.frame,  FRAME_COLOR);
            }
            def.setColor(ElementsNames.arrow,  ARROW_COLOR);

            // improve text legibility
            String lang = Messages.getLanguage(_context);
            int smallSize = SIZE_MONO;
            int legendSize = SIZE_LEGEND;
            int largeSize = SIZE_TITLE;
            if ("ar".equals(lang) || "ja".equals(lang) || ("zh".equals(lang) && !IS_WIN)) {
                smallSize += 2;
                legendSize += 2;
                largeSize += 3;
            }
            String ssmall = _context.getProperty(PROP_FONT_MONO, DEFAULT_FONT_NAME);
            String slegend = _context.getProperty(PROP_FONT_LEGEND, DEFAULT_LEGEND_FONT_NAME);
            String stitle = _context.getProperty(PROP_FONT_TITLE, DEFAULT_TITLE_FONT_NAME);
            Font small = new Font(ssmall, Font.PLAIN, smallSize);
            Font legnd = new Font(slegend, Font.PLAIN, legendSize);
            Font large = new Font(stitle, Font.PLAIN, largeSize);
            // DEFAULT is unused since we set all the others
            def.setFont(RrdGraphDef.FONTTAG_DEFAULT, small);
            // AXIS is unused, we do not set any axis labels
            def.setFont(RrdGraphDef.FONTTAG_AXIS, small);
            // rrd4j sets UNIT = AXIS in RrdGraphConstants, may be bug, maybe not, no use setting them different here
            def.setFont(RrdGraphDef.FONTTAG_UNIT, small);
            def.setFont(RrdGraphDef.FONTTAG_LEGEND, legnd);
            def.setFont(RrdGraphDef.FONTTAG_TITLE, large);

            boolean localTime = !_context.getBooleanProperty(GraphConstants.PROP_UTC);
            if (localTime)
                def.setTimeZone(SystemVersion.getSystemTimeZone(_context));
            def.setMinValue(0d);
            String name = _listener.getRate().getRateStat().getName();
            // heuristic to set K=1024
            //if ((name.startsWith("bw.") || name.indexOf("Size") >= 0 || name.indexOf("Bps") >= 0 || name.indexOf("memory") >= 0)
            if ((name.indexOf("Size") >= 0 || name.indexOf("memory") >= 0)
                && !showEvents)
                def.setBase(1024);
            if (titleOverride != null) {
                def.setTitle(titleOverride);
            } else if (!hideTitle) {
                String title;
                String p;
                // we want the formatting and translation of formatDuration2(), except not zh, and not the &nbsp;
                if (IS_WIN && "zh".equals(Messages.getLanguage(_context)))
                    p = DataHelper.formatDuration(period);
                else
                    p = DataHelper.formatDuration2(period).replace("&nbsp;", " ");
                if (showEvents)
                    title = name + ' ' + _t("events in {0}", p);
                else
                    title = name + ' ' + _t("averaged for {0}", p);
                def.setTitle(title);
            }
            String path = _listener.getData().getPath();
            String dsNames[] = _listener.getData().getDsNames();
            String plotName;
            String descr;
            if (showEvents) {
                // include the average event count on the plot
                plotName = dsNames[1];
                descr = _t("Events per period");
            } else {
                // include the average value
                plotName = dsNames[0];
                // The descriptions are not tagged in the createRateStat calls
                // (there are over 500 of them)
                // but the descriptions for the default graphs are tagged in
                // Strings.java
                descr = _t(_listener.getRate().getRateStat().getDescription());
            }

            //long started = ((RouterContext)_context).router().getWhenStarted();
            //if (started > start && started < end)
            //    def.vrule(started / 1000, RESTART_BAR_COLOR, _t("Restart"), 4.0f);

            def.datasource(plotName, path, plotName, SummaryListener.CF, _listener.getBackendFactory());
            Color areaColor = isDark ? AREA_COLOR_DARK : AREA_COLOR;
            if (descr.length() > 0) {
                def.area(plotName, areaColor, descr + "\\l");
            } else {
                def.area(plotName, areaColor);
            }
            if (!hideLegend) {
                Variable var = new Variable.AVERAGE();
                def.datasource("avg", plotName, var);
                def.gprint("avg", "   " + _t("Avg") + ": %.2f%s");
                var = new Variable.MAX();
                def.datasource("max", plotName, var);
                def.gprint("max", ' ' + _t("Max") + ": %.2f%S");
                var = new Variable.LAST();
                def.datasource("last", plotName, var);
                def.gprint("last", ' ' + _t("Now") + ": %.2f%S\\l");
            }
            String plotName2 = null;
            if (lsnr2 != null) {
                String dsNames2[] = lsnr2.getData().getDsNames();
                plotName2 = dsNames2[0];
                String path2 = lsnr2.getData().getPath();
                String descr2 = _t(lsnr2.getRate().getRateStat().getDescription());
                def.datasource(plotName2, path2, plotName2, SummaryListener.CF, lsnr2.getBackendFactory());
                Color lineColor = isDark ? LINE_COLOR_DARK : LINE_COLOR;
                def.line(plotName2, lineColor, descr2 + "\\l", 2);
                if (!hideLegend) {
                    Variable var = new Variable.AVERAGE();
                    def.datasource("avg2", plotName2, var);
                    def.gprint("avg2", "   " + _t("Avg") + ": %.2f%s");
                    var = new Variable.MAX();
                    def.datasource("max2", plotName2, var);
                    def.gprint("max2", ' ' + _t("Max") + ": %.2f%S");
                    var = new Variable.LAST();
                    def.datasource("last2", plotName2, var);
                    def.gprint("last2", ' ' + _t("Now") + ": %.2f%S\\l");
                }
            }
            if (!hideLegend) {
                // '07 Jul 21:09' with month name in the system locale
                // TODO: Fix Arabic time display
                Map<Long, String> events = ((RouterContext)_context).router().eventLog().getEvents(EventLog.STARTED, start);
                Color restartBarColor = isDark ? RESTART_BAR_COLOR_DARK : RESTART_BAR_COLOR;
                if (localTime) {
                    for (Map.Entry<Long, String> event : events.entrySet()) {
                        long started = event.getKey().longValue();
                        if (started > start && started < end) {
                            String legend;
                            if (Messages.isRTL(lang)) {
                                // RTL languages
                                legend = _t("Restart") + ' ' + DataHelper.formatTime(started) + " - " + event.getValue() + "\\l";
                            } else {
                                legend = _t("Restart") + ' ' + DataHelper.formatTime(started) + " [" + event.getValue() + "]\\l";
                            }
                            def.vrule(started / 1000, restartBarColor, legend, 2.0f);
                        }
                    }
                    def.comment(DataHelper.formatTime(start) + " — " + DataHelper.formatTime(end) + "\\r");
                } else {
                    SimpleDateFormat sdf = new SimpleDateFormat("dd MMM HH:mm");
                    for (Map.Entry<Long, String> event : events.entrySet()) {
                        long started = event.getKey().longValue();
                        if (started > start && started < end) {
                            String legend;
                            if (Messages.isRTL(lang)) {
                                // RTL languages
                                legend = _t("Restart") + ' ' + sdf.format(new Date(started)) + " - " + event.getValue() + "\\l";
                            } else {
                                legend = _t("Restart") + ' ' + sdf.format(new Date(started)) + " [" + event.getValue() + "]\\l";
                            }
                            def.vrule(started / 1000, restartBarColor, legend, 2.0f);
                        }
                    }
                    def.comment(sdf.format(new Date(start)) + " — " + sdf.format(new Date(end)) + " UTC\\r");
                }
            }
            if (!showCredit)
                def.setShowSignature(false);
            /*
            // these four lines set up a graph plotting both values and events on the same chart
            // (but with the same coordinates, so the values may look pretty skewed)
                def.datasource(dsNames[0], path, dsNames[0], "AVERAGE", "MEMORY");
                def.datasource(dsNames[1], path, dsNames[1], "AVERAGE", "MEMORY");
                def.area(dsNames[0], AREA_COLOR, _listener.getRate().getRateStat().getDescription());
                def.line(dsNames[1], LINE_COLOR, "Events per period");
            */
            if (hideLegend)
                def.setNoLegend(true);
            if (hideGrid) {
                def.setDrawXGrid(false);
                def.setDrawYGrid(false);
            }
            //System.out.println("rendering: path=" + path + " dsNames[0]=" + dsNames[0] + " dsNames[1]=" + dsNames[1] + " lsnr.getName=" + _listener.getName());
            def.setAntiAliasing(false);
            def.setTextAntiAliasing(true);
            def.setGridStroke(GRID_STROKE);
            def.setTickStroke(TICK_STROKE);
            //System.out.println("Rendering: \n" + def.exportXmlTemplate());
            //System.out.println("*****************\nData: \n" + _listener.getData().dump());
            def.setWidth(width);
            def.setHeight(height);
            def.setImageFormat("PNG");
            def.setLazy(true);

            RrdGraph graph;
            try {
                // NPE here if system is missing fonts - see ticket #915
                graph = new RrdGraph(def);
            } catch (NullPointerException npe) {
                _log.error("Error rendering graph", npe);
                StatSummarizer.setDisabled(_context);
                throw new IOException("Error rendering - disabling graph generation. Missing font?");
            } catch (Error e) {
                // Docker InternalError see Gitlab #383
                _log.error("Error rendering graph", e);
                StatSummarizer.setDisabled(_context);
                throw new IOException("Error rendering - disabling graph generation. Missing font?");
            }
            int totalWidth = graph.getRrdGraphInfo().getWidth();
            int totalHeight = graph.getRrdGraphInfo().getHeight();
            BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_USHORT_565_RGB);
            Graphics gfx = img.getGraphics();
            graph.render(gfx);
            ios = new MemoryCacheImageOutputStream(out);
            ImageIO.write(img, "png", ios);

            _context.statManager().addRateData("graph.renderTime", System.currentTimeMillis() - begin);
        } catch (RrdException re) {
            _log.error("Error rendering", re);
            throw new IOException("Error plotting: " + re.getLocalizedMessage());
        } catch (IOException ioe) {
            // typically org.mortbay.jetty.EofException extends java.io.EOFException
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error rendering", ioe);
            throw ioe;
        } catch (OutOfMemoryError oom) {
            _log.error("Error rendering", oom);
            throw new IOException("Error plotting: " + oom.getLocalizedMessage());
        } finally {
            // this does not close the underlying stream
            if (ios != null) try {ios.close();} catch (IOException ioe) {}
        }
    }

    /** translate a string */
    private String _t(String s) {
        // the RRD font doesn't have zh chars, at least on my system
        // Works on 1.5.9 except on windows
        if (IS_WIN && "zh".equals(Messages.getLanguage(_context)))
            return s;
        return Messages.getString(s, _context);
    }

    /**
     *  translate a string with a parameter
     */
    private String _t(String s, String o) {
        // the RRD font doesn't have zh chars, at least on my system
        // Works on 1.5.9 except on windows
        if (IS_WIN && "zh".equals(Messages.getLanguage(_context)))
            return s.replace("{0}", o);
        return Messages.getString(s, o, _context);
    }
}
