package net.i2p.router.web;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
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

import org.jrobin.core.RrdException;
import org.jrobin.graph.RrdGraph;
import org.jrobin.graph.RrdGraphDef;

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
    private static final Color BACK_COLOR = new Color(246, 246, 255);
    private static final Color SHADEA_COLOR = new Color(246, 246, 255);
    private static final Color SHADEB_COLOR = new Color(246, 246, 255);
    private static final Color GRID_COLOR = new Color(100, 100, 100, 75);
    private static final Color MGRID_COLOR = new Color(255, 91, 91, 110);
    private static final Color FONT_COLOR = new Color(51, 51, 63);
    private static final Color FRAME_COLOR = new Color(51, 51, 63);
    private static final Color AREA_COLOR = new Color(100, 160, 200, 200);
    private static final Color LINE_COLOR = new Color(0, 30, 110, 255);
    private static final Color RESTART_BAR_COLOR = new Color(223, 13, 13, 255);
    private static final String DEFAULT_FONT_NAME = System.getProperty("os.name").toLowerCase().contains("windows") ?
            "Lucida Console" : "Monospaced";

    public SummaryRenderer(I2PAppContext ctx, SummaryListener lsnr) { 
        _log = ctx.logManager().getLog(SummaryRenderer.class);
        _listener = lsnr;
        _context = ctx;
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
/*****
        long end = ctx.clock().now() - 60*1000;
        long start = end - 60*1000*SummaryListener.PERIODS;
        try {
            RrdGraphDefTemplate template = new RrdGraphDefTemplate(filename);
            RrdGraphDef def = template.getRrdGraphDef();
            def.setTimeSpan(start/1000, end/1000); // ignore the periods in the template
            // FIXME not clear how to get the height and width from the template
            int width = DEFAULT_X;
            int height = DEFAULT_Y;
            def.setWidth(width);
            def.setHeight(height);

            RrdGraph graph = new RrdGraph(def);
            int totalWidth = graph.getRrdGraphInfo().getWidth();
            int totalHeight = graph.getRrdGraphInfo().getHeight();
            BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_USHORT_565_RGB);
            Graphics gfx = img.getGraphics();
            graph.render(gfx);
            ImageOutputStream ios = new MemoryCacheImageOutputStream(out);
            ImageIO.write(img, "png", ios);
        } catch (RrdException re) {
            //_log.error("Error rendering " + filename, re);
            throw new IOException("Error plotting: " + re.getMessage());
        } catch (IOException ioe) {
            //_log.error("Error rendering " + filename, ioe);
            throw ioe;
        }
*****/
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
        // prevent NaNs if we are skewed ahead of system time
        long end = Math.min(_listener.now(), System.currentTimeMillis()) - 75*1000;
        long period = _listener.getRate().getPeriod();
        if (endp > 0)
            end -= period * endp;
        if (periodCount <= 0 || periodCount > _listener.getRows())
            periodCount = _listener.getRows();
        long start = end - (period * periodCount);
        //long begin = System.currentTimeMillis();
        ImageOutputStream ios = null;
        try {
            RrdGraphDef def = new RrdGraphDef();

            // Override defaults
            def.setColor(RrdGraphDef.COLOR_BACK,   BACK_COLOR);
            def.setColor(RrdGraphDef.COLOR_SHADEA, SHADEA_COLOR);
            def.setColor(RrdGraphDef.COLOR_SHADEB, SHADEB_COLOR);
            def.setColor(RrdGraphDef.COLOR_GRID,   GRID_COLOR);
            def.setColor(RrdGraphDef.COLOR_MGRID,  MGRID_COLOR);
            def.setColor(RrdGraphDef.COLOR_FONT,   FONT_COLOR);
            def.setColor(RrdGraphDef.COLOR_FRAME,  FRAME_COLOR);
            def.setFont(RrdGraphDef.FONTTAG_DEFAULT, new Font(DEFAULT_FONT_NAME, Font.PLAIN, 10));
            def.setFont(RrdGraphDef.FONTTAG_TITLE,   new Font(DEFAULT_FONT_NAME, Font.PLAIN, 10));
            def.setFont(RrdGraphDef.FONTTAG_AXIS,    new Font("Droid Sans Mono", Font.PLAIN, 10));
            def.setFont(RrdGraphDef.FONTTAG_UNIT,    new Font(DEFAULT_FONT_NAME, Font.PLAIN, 10));
            def.setFont(RrdGraphDef.FONTTAG_LEGEND,  new Font("Droid Sans Mono", Font.PLAIN, 10));

            // improve text legibility
            String lang = Messages.getLanguage(_context);
            Font small = def.getSmallFont();
            Font large = def.getLargeFont();
            if ("ar".equals(lang) || "jp".equals(lang) || ("zh".equals(lang) && !IS_WIN)) {
                small = small.deriveFont(small.getSize2D() + 2.0f);
                large = large.deriveFont(Font.PLAIN, large.getSize2D() + 3.0f);
            } else {
            //    small = small.deriveFont(small.getSize2D() + 1.0f);
            // if specified font family is missing, jrobin will use fallback
                  small = new Font("Droid Sans Mono", Font.PLAIN, 10);
            //    large = large.deriveFont(large.getSize2D() + 1.0f);
                  large = new Font("Droid Sans", Font.PLAIN, 13);
            }
            def.setSmallFont(small);
            def.setLargeFont(large);

            def.setTimeSpan(start/1000, end/1000);
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

            def.datasource(plotName, path, plotName, SummaryListener.CF, _listener.getBackendName());
            if (descr.length() > 0) {
                def.area(plotName, AREA_COLOR, descr + "\\l");
            } else {
                def.area(plotName, AREA_COLOR);
            }
            if (!hideLegend) {
                def.gprint(plotName, SummaryListener.CF, "   " + _t("Avg") + ": %.2f%s");
                def.gprint(plotName, "MAX", ' ' + _t("Max") + ": %.2f%S");
                def.gprint(plotName, "LAST", ' ' + _t("Now") + ": %.2f%S\\l");
            }
            String plotName2 = null;
            if (lsnr2 != null) {
                String dsNames2[] = lsnr2.getData().getDsNames();
                plotName2 = dsNames2[0];
                String path2 = lsnr2.getData().getPath();
                String descr2 = _t(lsnr2.getRate().getRateStat().getDescription());
                def.datasource(plotName2, path2, plotName2, SummaryListener.CF, lsnr2.getBackendName());
                def.line(plotName2, LINE_COLOR, descr2 + "\\l", 2);
                if (!hideLegend) {
                    def.gprint(plotName2, SummaryListener.CF, "   " + _t("Avg") + ": %.2f%s");
                    def.gprint(plotName2, "MAX", ' ' + _t("Max") + ": %.2f%S");
                    def.gprint(plotName2, "LAST", ' ' + _t("Now") + ": %.2f%S\\l");
                }
            }
            if (!hideLegend) {
                // '07 Jul 21:09' with month name in the system locale
                // TODO: Fix Arabic time display
                SimpleDateFormat sdf = new SimpleDateFormat("dd MMM HH:mm");
                Map<Long, String> events = ((RouterContext)_context).router().eventLog().getEvents(EventLog.STARTED, start);
                for (Map.Entry<Long, String> event : events.entrySet()) {
                    long started = event.getKey().longValue();
                    if (started > start && started < end) {
                        // String legend = _t("Restart") + ' ' + sdf.format(new Date(started)) + " UTC " + event.getValue() + "\\l";
                        if ("ar".equals(lang)) {
                            String legend = _t("Restart") + ' ' + sdf.format(new Date(started)) + " - " + event.getValue() + "\\l";
                            def.vrule(started / 1000, RESTART_BAR_COLOR, legend, 2.0f);
                        } else {
                            String legend = _t("Restart") + ' ' + sdf.format(new Date(started)) + " [" + event.getValue() + "]\\l";
                            def.vrule(started / 1000, RESTART_BAR_COLOR, legend, 2.0f);
                        }
                    }
                }
                def.comment(sdf.format(new Date(start)) + " — " + sdf.format(new Date(end)) + " UTC\\r");
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
                _log.error("Error rendering", npe);
                StatSummarizer.setDisabled();
                throw new IOException("Error rendering - disabling graph generation. Missing font? See http://trac.i2p2.i2p/ticket/915");
            }
            int totalWidth = graph.getRrdGraphInfo().getWidth();
            int totalHeight = graph.getRrdGraphInfo().getHeight();
            BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_USHORT_565_RGB);
            Graphics gfx = img.getGraphics();
            graph.render(gfx);
            ios = new MemoryCacheImageOutputStream(out);
            ImageIO.write(img, "png", ios);
            //System.out.println("Graph created");

            //File t = File.createTempFile("jrobinData", ".xml");
            //_listener.getData().dumpXml(new FileOutputStream(t));
            //System.out.println("plotted: " + (data != null ? data.length : 0) + " bytes in " + timeToPlot
            //                   ); // + ", data written to " + t.getAbsolutePath());
        } catch (RrdException re) {
            _log.error("Error rendering", re);
            throw new IOException("Error plotting: " + re.getMessage());
        } catch (IOException ioe) {
            // typically org.mortbay.jetty.EofException extends java.io.EOFException
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error rendering", ioe);
            throw ioe;
        } catch (OutOfMemoryError oom) {
            _log.error("Error rendering", oom);
            throw new IOException("Error plotting: " + oom.getMessage());
        } finally {
            // this does not close the underlying stream
            if (ios != null) try {ios.close();} catch (IOException ioe) {}
        }
    }

    private static final boolean IS_WIN = SystemVersion.isWindows();

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
