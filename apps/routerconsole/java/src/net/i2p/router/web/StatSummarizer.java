package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import static net.i2p.router.web.GraphConstants.*;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 *  A thread started by RouterConsoleRunner that
 *  checks the configuration for stats to be tracked via jrobin,
 *  and adds or deletes RRDs as necessary.
 *
 *  This also contains methods to generate xml or png image output.
 *  The rendering for graphs is in SummaryRenderer.
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
    // TODO remove static instance
    private static StatSummarizer _instance;
    private static final int MAX_CONCURRENT_PNG = SystemVersion.isARM() ? 2 : 3;
    private final Semaphore _sem;
    private volatile boolean _isRunning = true;
    private boolean _isDisabled;
    private Thread _thread;
    
    public StatSummarizer() {
        _context = RouterContext.listContexts().get(0); // only summarize one per jvm
        _log = _context.logManager().getLog(getClass());
        _listeners = new CopyOnWriteArrayList<SummaryListener>();
        _instance = this;
        _sem = new Semaphore(MAX_CONCURRENT_PNG, true);
        _context.addShutdownTask(new Shutdown());
    }
    
    public static StatSummarizer instance() { return _instance; }
    
    public void run() {
        // JRobin 1.5.9 crashes these JVMs
        if (SystemVersion.isApache() ||            // Harmony
            SystemVersion.isGNU()) {               // JamVM or gij
            _log.logAlways(Log.WARN, "Graphing not supported with this JVM: " +
                                     System.getProperty("java.vendor") + ' ' +
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
    
    /** @since 0.8.7, public since 0.9.33, was package private */
    public static boolean isDisabled() {
        return _instance == null || _instance._isDisabled;
    }
    
    /**
     * Disable graph generation until restart
     * See SummaryRenderer.render()
     * @since 0.9.6
     */
    static void setDisabled() {
        if (_instance != null) {
            _instance._isDisabled = true;
            _instance._isRunning = false;
        }
    }

    /**
     *  List of SummaryListener instances
     *  @since public since 0.9.33, was package private
     */
    public List<SummaryListener> getListeners() { return _listeners; }
    
    /**  @since public since 0.9.33, was package private */
    public static final String DEFAULT_DATABASES = "bw.sendRate.60000" +
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
        
        Set<Rate> old = parseSpecs(oldSpecs);
        Set<Rate> newSpecs = parseSpecs(spec);
        
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
        return renderPng(rate, out, DEFAULT_X, DEFAULT_Y,
                         false, false, false, false, -1, 0, true); 
    }

    /**
     *  This does the single data graphs.
     *  For the two-data bandwidth graph see renderRatePng().
     *  Synchronized to conserve memory.
     *
     *  @param end number of periods before now
     *  @return success
     */
    public boolean renderPng(Rate rate, OutputStream out, int width, int height, boolean hideLegend,
                                          boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount,
                                          int end, boolean showCredit) throws IOException {
        try {
            try {
                _sem.acquire();
            } catch (InterruptedException ie) {}
            try {
                return locked_renderPng(rate, out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                                    periodCount, end, showCredit);
            } catch (NoClassDefFoundError ncdfe) {
                //  java.lang.NoClassDefFoundError: Could not initialize class sun.awt.X11FontManager
                //  at java.lang.Class.forName0(Native Method)
                //  at java.lang.Class.forName(Class.java:270)
                //  at sun.font.FontManagerFactory$1.run(FontManagerFactory.java:82)
                _isDisabled = true;
                _isRunning = false;
                String s = "Error rendering - disabling graph generation. Install ttf-dejavu font package?";
                _log.logAlways(Log.WARN, s);
                IOException ioe = new IOException(s);
                ioe.initCause(ncdfe);
                throw ioe;
            }
        } finally {
            _sem.release();
        }
    }

    /**
     *  @param end number of periods before now
     */
    private boolean locked_renderPng(Rate rate, OutputStream out, int width, int height, boolean hideLegend,
                                          boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount,
                                          int end, boolean showCredit) throws IOException {
        if (width > MAX_X)
            width = MAX_X;
        else if (width <= 0)
            width = DEFAULT_X;
        if (height > MAX_Y)
            height = MAX_Y;
        else if (height <= 0)
            height = DEFAULT_Y;
        if (end < 0)
            end = 0;
        for (SummaryListener lsnr : _listeners) {
            if (lsnr.getRate().equals(rate)) {
                lsnr.renderPng(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, end, showCredit);
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
                out.write(DataHelper.getUTF8("<!-- Rate: " + lsnr.getRate().getRateStat().getName() + " for period " + lsnr.getRate().getPeriod() + " -->\n"));
                out.write(DataHelper.getUTF8("<!-- Average data source name: " + lsnr.getName() + " event count data source name: " + lsnr.getEventName() + " -->\n"));
                return true;
            }
        }
        return false;
    }
    
    /**
     *  This does the two-data bandwidth graph only.
     *  For all other graphs see renderPng() above.
     *  Synchronized to conserve memory.
     *
     *  @param end number of periods before now
     *  @return success
     */
    public boolean renderRatePng(OutputStream out, int width, int height, boolean hideLegend,
                                              boolean hideGrid, boolean hideTitle, boolean showEvents,
                                              int periodCount, int end, boolean showCredit) throws IOException {
        try {
            try {
                _sem.acquire();
            } catch (InterruptedException ie) {}
            try {
                return locked_renderRatePng(out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                                        periodCount, end, showCredit);
            } catch (NoClassDefFoundError ncdfe) {
                //  java.lang.NoClassDefFoundError: Could not initialize class sun.awt.X11FontManager
                //  at java.lang.Class.forName0(Native Method)
                //  at java.lang.Class.forName(Class.java:270)
                //  at sun.font.FontManagerFactory$1.run(FontManagerFactory.java:82)
                _isDisabled = true;
                _isRunning = false;
                String s = "Error rendering - disabling graph generation. Install ttf-dejavu font package?";
                _log.logAlways(Log.WARN, s);
                IOException ioe = new IOException(s);
                ioe.initCause(ncdfe);
                throw ioe;
            }
        } finally {
            _sem.release();
        }
    }

    private boolean locked_renderRatePng(OutputStream out, int width, int height, boolean hideLegend,
                                              boolean hideGrid, boolean hideTitle, boolean showEvents,
                                              int periodCount, int end, boolean showCredit) throws IOException {

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

        if (width > MAX_X)
            width = MAX_X;
        else if (width <= 0)
            width = DEFAULT_X;
        if (height > MAX_Y)
            height = MAX_Y;
        else if (height <= 0)
            height = DEFAULT_Y;
        txLsnr.renderPng(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount,
                         end, showCredit, rxLsnr, _t("Bandwidth usage"));
        return true;
    }
    
    /**
     * @param specs statName.period,statName.period,statName.period
     * @return list of Rate objects
     * @since public since 0.9.33, was package private
     */
    public Set<Rate> parseSpecs(String specs) {
        if (specs == null)
            return Collections.emptySet();
        StringTokenizer tok = new StringTokenizer(specs, ",");
        Set<Rate> rv = new HashSet<Rate>();
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
