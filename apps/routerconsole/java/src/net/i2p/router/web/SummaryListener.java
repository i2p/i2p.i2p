package net.i2p.router.web;

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

/**
 *  Creates and updates the in-memory RRD database,
 *  and provides methods to generate graphs of the data
 *
 *  @since 0.6.1.13
 */
class SummaryListener implements RateSummaryListener {
    private final I2PAppContext _context;
    private final Log _log;
    private final Rate _rate;
    private String _name;
    private String _eventName;
    private RrdDb _db;
    private Sample _sample;
    private RrdMemoryBackendFactory _factory;
    private SummaryRenderer _renderer;
    
    static final int PERIODS = 1440;
    
    static {
        try {
            RrdBackendFactory.setDefaultFactory("MEMORY");
        } catch (RrdException re) {
            re.printStackTrace();
        }
    }
    
    public SummaryListener(Rate r) {
        _context = I2PAppContext.getGlobalContext();
        _rate = r;
        _log = _context.logManager().getLog(SummaryListener.class);
    }
    
    public void add(double totalValue, long eventCount, double totalEventTime, long period) {
        long now = now();
        long when = now / 1000;
        //System.out.println("add to " + getRate().getRateStat().getName() + " on " + System.currentTimeMillis() + " / " + now + " / " + when);
        if (_db != null) {
            // add one value to the db (the average value for the period)
            try {
                _sample.setTime(when);
                double val = eventCount > 0 ? (totalValue / (double)eventCount) : 0d;
                _sample.setValue(_name, val);
                _sample.setValue(_eventName, eventCount);
                //_sample.setValue(0, val);
                //_sample.setValue(1, eventCount);
                _sample.update();
                //String names[] = _sample.getDsNames();
                //System.out.println("Add " + val + " over " + eventCount + " for " + _name
                //                   + " [" + names[0] + ", " + names[1] + "]");
            } catch (IOException ioe) {
                _log.error("Error adding", ioe);
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

    public void startListening() {
        RateStat rs = _rate.getRateStat();
        long period = _rate.getPeriod();
        String baseName = rs.getName() + "." + period;
        _name = createName(_context, baseName);
        _eventName = createName(_context, baseName + ".events");
        try {
            RrdDef def = new RrdDef(_name, now()/1000, period/1000);
            // for info on the heartbeat, xff, steps, etc, see the rrdcreate man page, aka
            // http://www.jrobin.org/support/man/rrdcreate.html
            long heartbeat = period*10/1000;
            def.addDatasource(_name, "GAUGE", heartbeat, Double.NaN, Double.NaN);
            def.addDatasource(_eventName, "GAUGE", heartbeat, 0, Double.NaN);
            double xff = 0.9;
            int steps = 1;
            int rows = PERIODS;
            def.addArchive("AVERAGE", xff, steps, rows);
            _factory = (RrdMemoryBackendFactory)RrdBackendFactory.getDefaultFactory();
            _db = new RrdDb(def, _factory);
            _sample = _db.createSample();
            _renderer = new SummaryRenderer(_context, this);
            _rate.setSummaryListener(this);
            // Typical usage is 23456 bytes ~= 1440 * 16
            if (_log.shouldLog(Log.INFO))
                _log.info("New RRD " + baseName + " consuming " + _db.getRrdBackend().getLength() + " bytes");
        } catch (RrdException re) {
            _log.error("Error starting", re);
        } catch (IOException ioe) {
            _log.error("Error starting", ioe);
        }
    }

    public void stopListening() {
        if (_db == null) return;
        try {
            _db.close();
        } catch (IOException ioe) {
            _log.error("Error closing", ioe);
        }
        _rate.setSummaryListener(null);
        _factory.delete(_db.getPath());
        _db = null;
    }

    public void renderPng(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount, boolean showCredit) throws IOException {
        _renderer.render(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, showCredit); 
    }

    public void renderPng(OutputStream out) throws IOException { _renderer.render(out); }
 
    String getName() { return _name; }

    String getEventName() { return _eventName; }

    RrdDb getData() { return _db; }

    long now() { return _context.clock().now(); }
    
    @Override
    public boolean equals(Object obj) {
        return ((obj instanceof SummaryListener) && ((SummaryListener)obj)._rate.equals(_rate));
    }

    @Override
    public int hashCode() { return _rate.hashCode(); }
}
