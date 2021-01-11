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

import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;
import org.rrd4j.core.Archive;
import org.rrd4j.core.RrdBackendFactory;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdDef;
import org.rrd4j.core.RrdException;
import org.rrd4j.core.RrdMemoryBackendFactory;
import org.rrd4j.core.RrdNioBackendFactory;
import org.rrd4j.core.Sample;

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
    static final String RRD_PREFIX = "rrd-";
    static final String RRD_SUFFIX = ".jrb";
    static final ConsolFun CF = ConsolFun.AVERAGE;
    static final DsType DS = DsType.GAUGE;
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
            } catch (RrdException re) {
                // this can happen after the time slews backwards, so don't make it an error
                // org.jrobin.core.RrdException: Bad sample timestamp 1264343107. Last update time was 1264343172, at least one second step is required
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error adding", re);
            } catch (IOException ioe) {
                _log.error("Error adding", ioe);
                stopListening();
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
            RrdBackendFactory factory = getBackendFactory();
            String rrdDefName;
            if (_isPersistent) {
                // generate full path for persistent RRD files
                File rrdDir = new SecureFile(_context.getRouterDir(), RRD_DIR);
                rrdFile = new File(rrdDir, RRD_PREFIX + _name + RRD_SUFFIX);
                rrdDefName = rrdFile.getAbsolutePath();
                if (rrdFile.exists()) {
                    _db = RrdDb.getBuilder().setPath(rrdDefName).setBackendFactory(factory).build();
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
                def.addDatasource(_name, DS, heartbeat, Double.NaN, Double.NaN);
                def.addDatasource(_eventName, DS, heartbeat, 0, Double.NaN);
                if (_isPersistent) {
                    _rows = (int) Math.max(MIN_ROWS, Math.min(MAX_ROWS, THREE_MONTHS / period));
                } else {
                    _rows = MIN_ROWS;
                }
                def.addArchive(CF, XFF, STEPS, _rows);
                _db = RrdDb.getBuilder().setRrdDef(def).setBackendFactory(factory).build();
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
        } catch (IllegalArgumentException iae) {
            // No backend from RrdBackendFactory
            _log.error("Error starting RRD for stat " + baseName, iae);
            _log.log(Log.CRIT, "rrd4j backend error, graphs disabled");
            System.out.println("rrd4j backend error, graphs disabled");
            StatSummarizer.setDisabled(_context);
        } catch (NoSuchMethodError nsme) {
            // Covariant fail Java 8/9/10
            // java.lang.NoSuchMethodError: java.nio.MappedByteBuffer.position(I)Ljava/nio/MappedByteBuffer;
            // see e.g. https://jira.mongodb.org/browse/JAVA-2559
            _log.error("Error starting RRD for stat " + baseName, nsme);
            String s = "Error:" +
                       "\nCompiler JDK mismatch with JRE version " + System.getProperty("java.version") +
                       " and no bootclasspath specified when building." +
                       "\nContact packager.";
            _log.log(Log.CRIT, s);
            System.out.println(s);
            StatSummarizer.setDisabled(_context);
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
            ((RrdMemoryBackendFactory)getBackendFactory(false)).delete(_db.getPath());
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
    
    /** @since 0.9.46 */
    RrdBackendFactory getBackendFactory() {
        return getBackendFactory(_isPersistent);
    }

    /** @since 0.9.46 */
    @SuppressWarnings("deprecation")
    private static RrdBackendFactory getBackendFactory(boolean isPersistent) {
        // getFactory(String) is deprecated, but to avoid it
        // we'd have to use findFactory(URI), but it only returns from the active factory list,
        // so we'd have to call addActiveFactories(getFactory(String)) anyway.
        //try {
            return isPersistent ? RrdBackendFactory.getDefaultFactory()                   // NIO
                                //: RrdBackendFactory.findFactory(new URI("memory:foo")); // MEMORY
                                : RrdBackendFactory.getFactory("MEMORY");                 // MEMORY
        //} catch (URISyntaxException use) {
        //    throw new IllegalArgumentException(use);
        //}
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
