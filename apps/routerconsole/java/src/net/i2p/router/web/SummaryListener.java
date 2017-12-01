package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.RateSummaryListener;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;

import org.jrobin.core.Archive;
import org.jrobin.core.RrdBackendFactory;
import org.jrobin.core.RrdDb;
import org.jrobin.core.RrdDef;
import org.jrobin.core.RrdException;
import org.jrobin.core.RrdMemoryBackendFactory;
import org.jrobin.core.RrdNioBackendFactory;
import org.jrobin.core.Sample;

/**
 *  Creates and updates the in-memory or on-disk RRD database,
 *  and provides methods to generate graphs of the data
 *
 *  @since 0.6.1.13
 */
public class SummaryListener implements RateSummaryListener {
    /** @since public since 0.9.33, was package private */
    public static final String PROP_PERSISTENT = "routerconsole.graphPersistent";
    /** note that .jrb files are NOT compatible with .rrd files */
    static final String RRD_DIR = "rrd";
    private static final String RRD_PREFIX = "rrd-";
    private static final String RRD_SUFFIX = ".jrb";
    static final String CF = "AVERAGE";
    private static final double XFF = 0.9d;
    private static final int STEPS = 1;

    private final I2PAppContext _context;
    private final Log _log;
    private final Rate _rate;
    private final boolean _isPersistent;
    private String _name;
    private String _eventName;
    private RrdDb _db;
    private Sample _sample;
    private SummaryRenderer _renderer;
    private int _rows;
    
    static final int PERIODS = 60 * 24;  // 1440
    private static final int MIN_ROWS = PERIODS;
    /** @since public since 0.9.33, was package private */
    public static final int MAX_ROWS = 91 * MIN_ROWS;
    private static final long THREE_MONTHS = 91l * 24 * 60 * 60 * 1000;
    
    public SummaryListener(Rate r) {
        _context = I2PAppContext.getGlobalContext();
        _rate = r;
        _log = _context.logManager().getLog(SummaryListener.class);
        _isPersistent = _context.getBooleanPropertyDefaultTrue(PROP_PERSISTENT);
    }
    
    public void add(double totalValue, long eventCount, double totalEventTime, long period) {
        long now = now();
        long when = now / 1000;
        //System.out.println("add to " + getRate().getRateStat().getName() + " on " + System.currentTimeMillis() + " / " + now + " / " + when);
        if (_db != null) {
            // add one value to the db (the average value for the period)
            try {
                _sample.setTime(when);
                double val = eventCount > 0 ? (totalValue / eventCount) : 0d;
                _sample.setValue(_name, val);
                _sample.setValue(_eventName, eventCount);
                //_sample.setValue(0, val);
                //_sample.setValue(1, eventCount);
                _sample.update();
                //String names[] = _sample.getDsNames();
                //System.out.println("Add " + val + " over " + eventCount + " for " + _name
                //                   + " [" + names[0] + ", " + names[1] + "]");
            } catch (IllegalArgumentException iae) {
                // ticket #1186
                // apparently a corrupt file, thrown from update()
                _log.error("Error adding", iae);
                String path = _isPersistent ? _db.getPath() : null;
                stopListening();
                if (path != null)
                    (new File(path)).delete();
            } catch (IOException ioe) {
                _log.error("Error adding", ioe);
                stopListening();
            } catch (RrdException re) {
                // this can happen after the time slews backwards, so don't make it an error
                // org.jrobin.core.RrdException: Bad sample timestamp 1264343107. Last update time was 1264343172, at least one second step is required
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error adding", re);
            }
        }
    }
    
    /**
     * JRobin can only deal with 20 character data source names, so we need to create a unique,
     * munged version from the user/developer-visible name.
     *
     */
    static String createName(I2PAppContext ctx, String wanted) { 
        return ctx.sha().calculateHash(DataHelper.getUTF8(wanted)).toBase64().substring(0,20);
    }
    
    public Rate getRate() { return _rate; }

    /**
     *  @return success
     */
    public boolean startListening() {
        RateStat rs = _rate.getRateStat();
        long period = _rate.getPeriod();
        String baseName = rs.getName() + "." + period;
        _name = createName(_context, baseName);
        _eventName = createName(_context, baseName + ".events");
        File rrdFile = null;
        try {
            RrdBackendFactory factory = RrdBackendFactory.getFactory(getBackendName());
            String rrdDefName;
            if (_isPersistent) {
                // generate full path for persistent RRD files
                File rrdDir = new SecureFile(_context.getRouterDir(), RRD_DIR);
                rrdFile = new File(rrdDir, RRD_PREFIX + _name + RRD_SUFFIX);
                rrdDefName = rrdFile.getAbsolutePath();
                if (rrdFile.exists()) {
                    _db = new RrdDb(rrdDefName, factory);
                    Archive arch = _db.getArchive(CF, STEPS);
                    if (arch == null)
                        throw new IOException("No average CF in " + rrdDefName);
                    _rows = arch.getRows();
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Existing RRD " + baseName + " (" + rrdDefName + ") with " + _rows + " rows consuming " + _db.getRrdBackend().getLength() + " bytes");
                } else {
                    rrdDir.mkdir();
                }
            } else {
                rrdDefName = _name;
            }
            if (_db == null) {
                // not persistent or not previously existing
                RrdDef def = new RrdDef(rrdDefName, now()/1000, period/1000);
                // for info on the heartbeat, xff, steps, etc, see the rrdcreate man page, aka
                // http://www.jrobin.org/support/man/rrdcreate.html
                long heartbeat = period*10/1000;
                def.addDatasource(_name, "GAUGE", heartbeat, Double.NaN, Double.NaN);
                def.addDatasource(_eventName, "GAUGE", heartbeat, 0, Double.NaN);
                if (_isPersistent) {
                    _rows = (int) Math.max(MIN_ROWS, Math.min(MAX_ROWS, THREE_MONTHS / period));
                } else {
                    _rows = MIN_ROWS;
                }
                def.addArchive(CF, XFF, STEPS, _rows);
                _db = new RrdDb(def, factory);
                if (_isPersistent)
                    SecureFileOutputStream.setPerms(new File(rrdDefName));
                if (_log.shouldLog(Log.INFO))
                    _log.info("New RRD " + baseName + " (" + rrdDefName + ") with " + _rows + " rows consuming " + _db.getRrdBackend().getLength() + " bytes");
            }
            _sample = _db.createSample();
            _renderer = new SummaryRenderer(_context, this);
            _rate.setSummaryListener(this);
            return true;
        } catch (OutOfMemoryError oom) {
            _log.error("Error starting RRD for stat " + baseName, oom);
        } catch (RrdException re) {
            _log.error("Error starting RRD for stat " + baseName, re);
            // corrupt file?
            if (_isPersistent && rrdFile != null)
                rrdFile.delete();
        } catch (IOException ioe) {
            _log.error("Error starting RRD for stat " + baseName, ioe);
        } catch (Throwable t) {
            _log.error("Error starting RRD for stat " + baseName, t);
        }
        return false;
    }

    public void stopListening() {
        if (_db == null) return;
        try {
            _db.close();
        } catch (IOException ioe) {
            _log.error("Error closing", ioe);
        }
        _rate.setSummaryListener(null);
        if (!_isPersistent) {
            // close() does not release resources for memory backend
            try {
                ((RrdMemoryBackendFactory)RrdBackendFactory.getFactory(RrdMemoryBackendFactory.NAME)).delete(_db.getPath());
            } catch (RrdException re) {}
        }
        _db = null;
    }

    /**
     *  Single graph.
     *
     *  @param end number of periods before now
     */
    public void renderPng(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid,
                          boolean hideTitle, boolean showEvents, int periodCount,
                          int end, boolean showCredit) throws IOException {
        renderPng(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount,
                  end, showCredit, null, null); 
    }

    /**
     *  Single or two-data-source graph.
     *
     *  @param lsnr2 2nd data source to plot on same graph, or null. Not recommended for events.
     *  @param titleOverride If non-null, overrides the title
     *  @since 0.9.6
     */
    public void renderPng(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid,
                          boolean hideTitle, boolean showEvents, int periodCount,
                          int end, boolean showCredit, SummaryListener lsnr2, String titleOverride) throws IOException {
        if (_renderer == null || _db == null)
            throw new IOException("No RRD, check logs for previous errors");
        _renderer.render(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount,
                         end, showCredit, lsnr2, titleOverride); 
    }

    public void renderPng(OutputStream out) throws IOException {
        if (_renderer == null || _db == null)
            throw new IOException("No RRD, check logs for previous errors");
        _renderer.render(out);
    }
 
    String getName() { return _name; }

    String getEventName() { return _eventName; }

    RrdDb getData() { return _db; }

    long now() { return _context.clock().now(); }
    
    /** @since 0.8.7 */
    String getBackendName() {
        return _isPersistent ? RrdNioBackendFactory.NAME : RrdMemoryBackendFactory.NAME;
    }

    /** @since 0.8.7 */
    int getRows() {
        return _rows;
    }

    @Override
    public boolean equals(Object obj) {
        return ((obj instanceof SummaryListener) && ((SummaryListener)obj)._rate.equals(_rate));
    }

    @Override
    public int hashCode() { return _rate.hashCode(); }
}
