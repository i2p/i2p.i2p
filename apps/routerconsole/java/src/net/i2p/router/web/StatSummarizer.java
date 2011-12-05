package net.i2p.router.web;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import net.i2p.router.RouterContext;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;

import org.jrobin.core.RrdException;
import org.jrobin.graph.RrdGraph;
import org.jrobin.graph.RrdGraphDef;

/**
 *  A thread started by RouterConsoleRunner that
 *  checks the configuration for stats to be tracked via jrobin,
 *  and adds or deletes RRDs as necessary.
 *
 *  This also contains methods to generate xml or png image output.
 *  The actual png rendering code is here for the special dual-rate graph;
 *  the rendering for standard graphs is in SummaryRenderer.
 *
 *  To control memory, the number of simultaneous renderings is limited.
 *
 *  @since 0.6.1.13
 */
public class StatSummarizer implements Runnable {
    private final RouterContext _context;
    private final Log _log;
    /** list of SummaryListener instances */
    private final List<SummaryListener> _listeners;
    private static StatSummarizer _instance;
    private static final int MAX_CONCURRENT_PNG = 3;
    private final Semaphore _sem;
    private volatile boolean _isRunning = true;
    private boolean _isDisabled;
    private Thread _thread;
    
    public StatSummarizer() {
        _context = RouterContext.listContexts().get(0); // fuck it, only summarize one per jvm
        _log = _context.logManager().getLog(getClass());
        _listeners = new CopyOnWriteArrayList();
        _instance = this;
        _sem = new Semaphore(MAX_CONCURRENT_PNG, true);
        _context.addShutdownTask(new Shutdown());
    }
    
    public static StatSummarizer instance() { return _instance; }
    
    public void run() {
        // JRobin 1.5.9 crashes these JVMs
        String vendor = System.getProperty("java.vendor");
        if (vendor.startsWith("Apache") ||                      // Harmony
            vendor.startsWith("GNU Classpath") ||               // JamVM
            vendor.startsWith("Free Software Foundation")) {    // gij
            _log.logAlways(Log.WARN, "Graphing not supported with this JVM: " +
                                     vendor + ' ' +
                                     System.getProperty("java.version") + " (" +
                                     System.getProperty("java.runtime.name") + ' ' +
                                     System.getProperty("java.runtime.version") + ')');
            _isDisabled = true;
            _isRunning = false;
            return;
        }
        boolean isPersistent = _context.getBooleanPropertyDefaultTrue(SummaryListener.PROP_PERSISTENT);
        if (!isPersistent)
            deleteOldRRDs();
        _thread = Thread.currentThread();
        String specs = "";
        while (_isRunning && _context.router().isAlive()) {
            specs = adjustDatabases(specs);
            try { Thread.sleep(60*1000); } catch (InterruptedException ie) {}
        }
    }
    
    /** @since 0.8.7 */
    static boolean isDisabled() {
        return _instance == null || _instance._isDisabled;
    }

    /** list of SummaryListener instances */
    List<SummaryListener> getListeners() { return _listeners; }
    
    private static final String DEFAULT_DATABASES = "bw.sendRate.60000" +
                                                    ",bw.recvRate.60000" +
//                                                  ",tunnel.testSuccessTime.60000" +
//                                                  ",udp.outboundActiveCount.60000" +
//                                                  ",udp.receivePacketSize.60000" +
//                                                  ",udp.receivePacketSkew.60000" +
//                                                  ",udp.sendConfirmTime.60000" +
//                                                  ",udp.sendPacketSize.60000" +
                                                    ",router.memoryUsed.60000" +
                                                    ",router.activePeers.60000";
//                                                  ",router.activeSendPeers.60000" +
//                                                  ",tunnel.acceptLoad.60000" +
//                                                  ",tunnel.dropLoadProactive.60000" +
//                                                  ",tunnel.buildExploratorySuccess.60000" +
//                                                  ",tunnel.buildExploratoryReject.60000" +
//                                                  ",tunnel.buildExploratoryExpire.60000" +
//                                                  ",client.sendAckTime.60000" +
//                                                  ",client.dispatchNoACK.60000" +
//                                                  ",ntcp.sendTime.60000" +
//                                                  ",ntcp.transmitTime.60000" +
//                                                  ",ntcp.sendBacklogTime.60000" +
//                                                  ",ntcp.receiveTime.60000" +
//                                                  ",transport.sendMessageFailureLifetime.60000" +
//                                                  ",transport.sendProcessingTime.60000";
    
    private String adjustDatabases(String oldSpecs) {
        String spec = _context.getProperty("stat.summaries", DEFAULT_DATABASES);
        if ( ( (spec == null) && (oldSpecs == null) ) ||
             ( (spec != null) && (oldSpecs != null) && (oldSpecs.equals(spec))) )
            return oldSpecs;
        
        List<Rate> old = parseSpecs(oldSpecs);
        List<Rate> newSpecs = parseSpecs(spec);
        
        // remove old ones
        for (Rate r : old) {
            if (!newSpecs.contains(r))
                removeDb(r);
        }
        // add new ones
        StringBuilder buf = new StringBuilder();
        boolean comma = false;
        for (Rate r : newSpecs) {
            if (!old.contains(r))
                addDb(r);
            if (comma)
                buf.append(',');
            else
                comma = true;
            buf.append(r.getRateStat().getName()).append(".").append(r.getPeriod());
        }
        return buf.toString();
    }
    
    private void removeDb(Rate r) {
        for (SummaryListener lsnr : _listeners) {
            if (lsnr.getRate().equals(r)) {
                // no iter.remove() in COWAL
                _listeners.remove(lsnr);
                lsnr.stopListening();
                return;
            }
        }
    }
    private void addDb(Rate r) {
        SummaryListener lsnr = new SummaryListener(r);
        boolean success = lsnr.startListening();
        if (success)
            _listeners.add(lsnr);
        else
            _log.error("Failed to add RRD for rate " + r.getRateStat().getName() + '.' + r.getPeriod());
        //System.out.println("Start listening for " + r.getRateStat().getName() + ": " + r.getPeriod());
    }

    public boolean renderPng(Rate rate, OutputStream out) throws IOException { 
        return renderPng(rate, out, GraphHelper.DEFAULT_X, GraphHelper.DEFAULT_Y,
                         false, false, false, false, -1, true); 
    }

    /**
     *  This does the single data graphs.
     *  For the two-data bandwidth graph see renderRatePng().
     *  Synchronized to conserve memory.
     *  @return success
     */
    public boolean renderPng(Rate rate, OutputStream out, int width, int height, boolean hideLegend,
                                          boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount,
                                          boolean showCredit) throws IOException {
        try {
            try {
                _sem.acquire();
            } catch (InterruptedException ie) {}
            return locked_renderPng(rate, out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                                    periodCount, showCredit);
        } finally {
            _sem.release();
        }
    }

    private boolean locked_renderPng(Rate rate, OutputStream out, int width, int height, boolean hideLegend,
                                          boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount,
                                          boolean showCredit) throws IOException {
        if (width > GraphHelper.MAX_X)
            width = GraphHelper.MAX_X;
        else if (width <= 0)
            width = GraphHelper.DEFAULT_X;
        if (height > GraphHelper.MAX_Y)
            height = GraphHelper.MAX_Y;
        else if (height <= 0)
            height = GraphHelper.DEFAULT_Y;
        for (SummaryListener lsnr : _listeners) {
            if (lsnr.getRate().equals(rate)) {
                lsnr.renderPng(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, showCredit);
                return true;
            }
        }
        return false;
    }

    /** @deprecated unused */
    public boolean renderPng(OutputStream out, String templateFilename) throws IOException {
        SummaryRenderer.render(_context, out, templateFilename);
        return true;
    }

    public boolean getXML(Rate rate, OutputStream out) throws IOException {
        try {
            try {
                _sem.acquire();
            } catch (InterruptedException ie) {}
            return locked_getXML(rate, out);
        } finally {
            _sem.release();
        }
    }

    private boolean locked_getXML(Rate rate, OutputStream out) throws IOException {
        for (SummaryListener lsnr : _listeners) {
            if (lsnr.getRate().equals(rate)) {
                lsnr.getData().exportXml(out);
                out.write(("<!-- Rate: " + lsnr.getRate().getRateStat().getName() + " for period " + lsnr.getRate().getPeriod() + " -->\n").getBytes());
                out.write(("<!-- Average data soure name: " + lsnr.getName() + " event count data source name: " + lsnr.getEventName() + " -->\n").getBytes());
                return true;
            }
        }
        return false;
    }
    
    /**
     *  This does the two-data bandwidth graph only.
     *  For all other graphs see SummaryRenderer
     *  Synchronized to conserve memory.
     *  @return success
     */
    public boolean renderRatePng(OutputStream out, int width, int height, boolean hideLegend,
                                              boolean hideGrid, boolean hideTitle, boolean showEvents,
                                              int periodCount, boolean showCredit) throws IOException {
        try {
            try {
                _sem.acquire();
            } catch (InterruptedException ie) {}
            return locked_renderRatePng(out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                                        periodCount, showCredit);
        } finally {
            _sem.release();
        }
    }

    private boolean locked_renderRatePng(OutputStream out, int width, int height, boolean hideLegend,
                                              boolean hideGrid, boolean hideTitle, boolean showEvents,
                                              int periodCount, boolean showCredit) throws IOException {

        // go to some trouble to see if we have the data for the combined bw graph
        SummaryListener txLsnr = null;
        SummaryListener rxLsnr = null;
        for (SummaryListener lsnr : StatSummarizer.instance().getListeners()) {
            String title = lsnr.getRate().getRateStat().getName();
            if (title.equals("bw.sendRate"))
                txLsnr = lsnr;
            else if (title.equals("bw.recvRate"))
                rxLsnr = lsnr;
        }
        if (txLsnr == null || rxLsnr == null)
            throw new IOException("no rates for combined graph");

        long end = _context.clock().now() - 75*1000;
        if (width > GraphHelper.MAX_X)
            width = GraphHelper.MAX_X;
        else if (width <= 0)
            width = GraphHelper.DEFAULT_X;
        if (height > GraphHelper.MAX_Y)
            height = GraphHelper.MAX_Y;
        else if (height <= 0)
            height = GraphHelper.DEFAULT_Y;
        if (periodCount <= 0 || periodCount > txLsnr.getRows())
            periodCount = txLsnr.getRows();
        long period = 60*1000;
        long start = end - period*periodCount;
        //long begin = System.currentTimeMillis();
        try {
            RrdGraphDef def = new RrdGraphDef();
            def.setTimeSpan(start/1000, end/1000);
            def.setMinValue(0d);
            def.setBase(1024);
            String title = _("Bandwidth usage");
            if (!hideTitle)
                def.setTitle(title);
            long started = _context.router().getWhenStarted();
            if (started > start && started < end)
                def.vrule(started / 1000, SummaryRenderer.RESTART_BAR_COLOR, null, 4.0f);  // no room for legend
            String sendName = SummaryListener.createName(_context, "bw.sendRate.60000");
            String recvName = SummaryListener.createName(_context, "bw.recvRate.60000");
            def.datasource(sendName, txLsnr.getData().getPath(), sendName, SummaryListener.CF, txLsnr.getBackendName());
            def.datasource(recvName, rxLsnr.getData().getPath(), recvName, SummaryListener.CF, rxLsnr.getBackendName());
            def.area(sendName, Color.BLUE, _("Outbound Bytes/sec"));
            //def.line(sendName, Color.BLUE, "Outbound bytes/sec", 3);
            def.line(recvName, Color.RED, _("Inbound Bytes/sec") + "\\r", 3);
            //def.area(recvName, Color.RED, "Inbound bytes/sec@r");
            if (!hideLegend) {
                def.gprint(sendName, SummaryListener.CF, _("Out average") + ": %.2f %s" + _("Bps"));
                def.gprint(sendName, "MAX", ' ' + _("max") + ": %.2f %S" + _("Bps") + "\\r");
                def.gprint(recvName, SummaryListener.CF, _("In average") + ": %.2f %S" + _("Bps"));
                def.gprint(recvName, "MAX", ' ' + _("max") + ": %.2f %S" + _("Bps") + "\\r");
            }
            if (!showCredit)
                def.setShowSignature(false);
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

            RrdGraph graph = new RrdGraph(def);
            //System.out.println("Graph created");
            int totalWidth = graph.getRrdGraphInfo().getWidth();
            int totalHeight = graph.getRrdGraphInfo().getHeight();
            BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_USHORT_565_RGB);
            Graphics gfx = img.getGraphics();
            graph.render(gfx);
            ImageOutputStream ios = new MemoryCacheImageOutputStream(out);
            ImageIO.write(img, "png", ios);

            //File t = File.createTempFile("jrobinData", ".xml");
            //_listener.getData().dumpXml(new FileOutputStream(t));
            //System.out.println("plotted: " + (data != null ? data.length : 0) + " bytes in " + timeToPlot
            //                   ); // + ", data written to " + t.getAbsolutePath());
            return true;
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
    
    /**
     * @param specs statName.period,statName.period,statName.period
     * @return list of Rate objects
     */
    private List<Rate> parseSpecs(String specs) {
        StringTokenizer tok = new StringTokenizer(specs, ",");
        List<Rate> rv = new ArrayList();
        while (tok.hasMoreTokens()) {
            String spec = tok.nextToken();
            int split = spec.lastIndexOf('.');
            if ( (split <= 0) || (split + 1 >= spec.length()) )
                continue;
            String name = spec.substring(0, split);
            String per = spec.substring(split+1);
            long period = -1;
            try { 
                period = Long.parseLong(per); 
                RateStat rs = _context.statManager().getRate(name);
                if (rs != null) {
                    Rate r = rs.getRate(period);
                    if (r != null)
                        rv.add(r);
                }
            } catch (NumberFormatException nfe) {}
        }
        return rv;
    }

    /**
     *  Delete the old rrd dir if we are no longer persistent
     *  @since 0.8.7
     */
    private void deleteOldRRDs() {
        File rrdDir = new File(_context.getRouterDir(), SummaryListener.RRD_DIR);
        FileUtil.rmdir(rrdDir, false);
    }

    private static final boolean IS_WIN = System.getProperty("os.name").startsWith("Win");

    /** translate a string */
    private String _(String s) {
        // the RRD font doesn't have zh chars, at least on my system
        // Works on 1.5.9 except on windows
        if (IS_WIN && "zh".equals(Messages.getLanguage(_context)))
            return s;
        return Messages.getString(s, _context);
    }

    /**
     *  Make sure any persistent RRDs are closed
     *  @since 0.8.7
     */
    private class Shutdown implements Runnable {
        public void run() {
            _isRunning = false;
            if (_thread != null)
                _thread.interrupt();
            for (SummaryListener lsnr : _listeners) {
                // FIXME could cause exceptions if rendering?
                lsnr.stopListening();
            }
            _listeners.clear();
        }
    }
}
