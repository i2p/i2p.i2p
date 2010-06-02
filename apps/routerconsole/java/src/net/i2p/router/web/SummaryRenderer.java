package net.i2p.router.web;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.RateSummaryListener;
import net.i2p.util.Log;

import org.jrobin.core.RrdBackendFactory;
import org.jrobin.core.RrdDb;
import org.jrobin.core.RrdDef;
import org.jrobin.core.RrdException;
import org.jrobin.core.RrdMemoryBackendFactory;
import org.jrobin.core.Sample;
import org.jrobin.graph.RrdGraph;
import org.jrobin.graph.RrdGraphDef;
import org.jrobin.graph.RrdGraphDefTemplate;

class SummaryRenderer {
    private Log _log;
    private SummaryListener _listener;
    private I2PAppContext _context;

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
     */
    public static synchronized void render(I2PAppContext ctx, OutputStream out, String filename) throws IOException {
        long end = ctx.clock().now() - 60*1000;
        long start = end - 60*1000*SummaryListener.PERIODS;
        try {
            RrdGraphDefTemplate template = new RrdGraphDefTemplate(filename);
            RrdGraphDef def = template.getRrdGraphDef();
            def.setTimePeriod(start/1000, end/1000); // ignore the periods in the template
            RrdGraph graph = new RrdGraph(def);
            byte img[] = graph.getPNGBytes();
            out.write(img);
        } catch (RrdException re) {
            //_log.error("Error rendering " + filename, re);
            throw new IOException("Error plotting: " + re.getMessage());
        } catch (IOException ioe) {
            //_log.error("Error rendering " + filename, ioe);
            throw ioe;
        }
    }

    public void render(OutputStream out) throws IOException { render(out, -1, -1, false, false, false, false, -1, false); }

    public void render(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount, boolean showCredit) throws IOException {
        long end = _listener.now() - 60*1000;
        if (periodCount <= 0) periodCount = SummaryListener.PERIODS;
        if (periodCount > SummaryListener.PERIODS)
            periodCount = SummaryListener.PERIODS;
        long start = end - _listener.getRate().getPeriod()*periodCount;
        //long begin = System.currentTimeMillis();
        try {
            RrdGraphDef def = new RrdGraphDef();
            def.setTimePeriod(start/1000, 0);
            def.setLowerLimit(0d);
            String name = _listener.getRate().getRateStat().getName();
            // heuristic to set K=1024
            if ((name.startsWith("bw.") || name.indexOf("Size") >= 0 || name.indexOf("Bps") >= 0 || name.indexOf("memory") >= 0)
                && !showEvents)
                def.setBaseValue(1024);
            if (!hideTitle) {
                String title;
                String p = DataHelper.formatDuration(_listener.getRate().getPeriod());
                if (showEvents)
                    // Note to translators: all runtime zh translation disabled in this file, no font available in RRD
                    title = name + ' ' + _("events in {0}", p);
                else
                    title = name + ' ' + _("averaged for {0}", p);
                def.setTitle(title);
            }
            String path = _listener.getData().getPath();
            String dsNames[] = _listener.getData().getDsNames();
            String plotName = null;
            String descr = null;
            if (showEvents) {
                // include the average event count on the plot
                plotName = dsNames[1];
                descr = _("Events per period");
            } else {
                // include the average value
                plotName = dsNames[0];
                // The descriptions are not tagged in the createRateStat calls
                // (there are over 500 of them)
                // but the descriptions for the default graphs are tagged in
                // Strings.java
                descr = _(_listener.getRate().getRateStat().getDescription());
            }
            def.datasource(plotName, path, plotName, "AVERAGE", "MEMORY");
            def.area(plotName, Color.BLUE, descr + "@r");
            if (!hideLegend) {
                def.gprint(plotName, "AVERAGE", _("avg") + ": @2@s");
                def.gprint(plotName, "MAX", ' ' + _("max") + ": @2@s");
                def.gprint(plotName, "LAST", ' ' + _("now") + ": @2@s@r");
            }
            if (!showCredit)
                def.setShowSignature(false);
            /*
            // these four lines set up a graph plotting both values and events on the same chart
            // (but with the same coordinates, so the values may look pretty skewed)
                def.datasource(dsNames[0], path, dsNames[0], "AVERAGE", "MEMORY");
                def.datasource(dsNames[1], path, dsNames[1], "AVERAGE", "MEMORY");
                def.area(dsNames[0], Color.BLUE, _listener.getRate().getRateStat().getDescription());
                def.line(dsNames[1], Color.RED, "Events per period");
            */
            if (hideLegend) 
                def.setShowLegend(false);
            if (hideGrid) {
                def.setGridX(false);
                def.setGridY(false);
            }
            //System.out.println("rendering: path=" + path + " dsNames[0]=" + dsNames[0] + " dsNames[1]=" + dsNames[1] + " lsnr.getName=" + _listener.getName());
            def.setAntiAliasing(false);
            //System.out.println("Rendering: \n" + def.exportXmlTemplate());
            //System.out.println("*****************\nData: \n" + _listener.getData().dump());
            RrdGraph graph = new RrdGraph(def);
            //System.out.println("Graph created");
            byte data[] = null;
            if ( (width <= 0) || (height <= 0) )
                data = graph.getPNGBytes();
            else
                data = graph.getPNGBytes(width, height);
            //long timeToPlot = System.currentTimeMillis() - begin;
            out.write(data);
            //File t = File.createTempFile("jrobinData", ".xml");
            //_listener.getData().dumpXml(new FileOutputStream(t));
            //System.out.println("plotted: " + (data != null ? data.length : 0) + " bytes in " + timeToPlot
            //                   ); // + ", data written to " + t.getAbsolutePath());
        } catch (RrdException re) {
            _log.error("Error rendering", re);
            throw new IOException("Error plotting: " + re.getMessage());
        } catch (IOException ioe) {
            _log.error("Error rendering", ioe);
            throw ioe;
        } catch (OutOfMemoryError oom) {
            _log.error("Error rendering", oom);
            throw new IOException("Error plotting: " + oom.getMessage());
        }
    }

    /** translate a string */
    private String _(String s) {
        // the RRD font doesn't have zh chars, at least on my system
        if ("zh".equals(Messages.getLanguage(_context)))
            return s;
        return Messages.getString(s, _context);
    }

    /**
     *  translate a string with a parameter
     */
    private String _(String s, String o) {
        // the RRD font doesn't have zh chars, at least on my system
        if ("zh".equals(Messages.getLanguage(_context)))
            return s.replace("{0}", o);
        return Messages.getString(s, o, _context);
    }
}
