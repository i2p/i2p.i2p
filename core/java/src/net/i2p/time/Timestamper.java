package net.i2p.time;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Periodically query a series of NTP servers and update any associated
 * listeners.  It tries the NTP servers in order, contacting them using 
 * SNTP (UDP port 123).  By default, it does this every 5 minutes, 
 * forever.
 */
public class Timestamper implements Runnable {
    private I2PAppContext _context;
    private Log _log;
    private List<String> _servers;
    private List<String> _priorityServers;
    private List<UpdateListener> _listeners;
    private int _queryFrequency;
    private int _concurringServers;
    private volatile boolean _disabled;
    private boolean _daemon;
    private boolean _initialized;
    private boolean _wellSynced;
    
    private static final int MIN_QUERY_FREQUENCY = 5*60*1000;
    private static final int DEFAULT_QUERY_FREQUENCY = 5*60*1000;
    private static final String DEFAULT_SERVER_LIST = "0.pool.ntp.org,1.pool.ntp.org,2.pool.ntp.org";
    private static final String DEFAULT_DISABLED = "true";
    /** how many times do we have to query if we are changing the clock? */
    private static final int DEFAULT_CONCURRING_SERVERS = 3;
    
    public static final String PROP_QUERY_FREQUENCY = "time.queryFrequencyMs";
    public static final String PROP_SERVER_LIST = "time.sntpServerList";
    public static final String PROP_DISABLED = "time.disabled";
    public static final String PROP_CONCURRING_SERVERS = "time.concurringServers";
    public static final String PROP_IP_COUNTRY = "i2np.lastCountry";
    
    /** if different SNTP servers differ by more than 10s, someone is b0rked */
    private static final int MAX_VARIANCE = 10*1000;
        
    public Timestamper(I2PAppContext ctx) {
        this(ctx, null, true);
    }
    
    public Timestamper(I2PAppContext ctx, UpdateListener lsnr) {
        this(ctx, lsnr, true);
    }
    public Timestamper(I2PAppContext ctx, UpdateListener lsnr, boolean daemon) {
        // Don't bother starting a thread if we are disabled.
        // This means we no longer check every 5 minutes to see if we got enabled,
        // so the property must be set at startup.
        // We still need to be instantiated since the router calls clock().getTimestamper().waitForInitialization()
        String disabled = ctx.getProperty(PROP_DISABLED, DEFAULT_DISABLED);
        if (Boolean.valueOf(disabled).booleanValue()) {
            _initialized = true;
            return;
        }
        _context = ctx;
        _daemon = daemon;
        _initialized = false;
        _wellSynced = false;
        _servers = new ArrayList(3);
        _listeners = new ArrayList(1);
        if (lsnr != null)
            _listeners.add(lsnr);
        updateConfig();
        startTimestamper();
    }
    
    public int getServerCount() { 
        synchronized (_servers) {
            return _servers.size(); 
        }
    }
    public String getServer(int index) { 
        synchronized (_servers) {
            return (String)_servers.get(index); 
        }
    }
    
    public int getQueryFrequencyMs() { return _queryFrequency; }
    
    public boolean getIsDisabled() { return _disabled; }
    
    public void addListener(UpdateListener lsnr) {
        synchronized (_listeners) {
            _listeners.add(lsnr);
        }
    }
    public void removeListener(UpdateListener lsnr) {
        synchronized (_listeners) {
            _listeners.remove(lsnr);
        }
    }
    public int getListenerCount() {
        synchronized (_listeners) {
            return _listeners.size();
        }
    }
    public UpdateListener getListener(int index) {
        synchronized (_listeners) {
            return (UpdateListener)_listeners.get(index);
        }
    }
    
    private void startTimestamper() {
        I2PThread t = new I2PThread(this, "Timestamper");
        t.setPriority(I2PThread.MIN_PRIORITY);
        t.setDaemon(_daemon);
        t.start();
    }
    
    public void waitForInitialization() {
        try { 
            synchronized (this) {
                if (!_initialized)
                    wait();
            }
        } catch (InterruptedException ie) {}
    }
    
    public void run() {
        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
        _log = _context.logManager().getLog(Timestamper.class);
        if (_log.shouldLog(Log.INFO))
            _log.info("Starting timestamper");
        boolean lastFailed = false;
        try {
            while (true) {
                updateConfig();
                if (!_disabled) {
                    // first the servers for our country, if we know what country we're in...
                    if (_priorityServers != null) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Querying servers " + _priorityServers);
                        try {
                            lastFailed = !queryTime(_priorityServers.toArray(new String[_priorityServers.size()]));
                        } catch (IllegalArgumentException iae) {
                            if ( (!lastFailed) && (_log.shouldLog(Log.WARN)) )
                                _log.warn("Unable to reach country-specific NTP servers");
                            lastFailed = true;
                        }
                    }
                    // ... and then the global list, if that failed
                    if (_priorityServers == null || lastFailed) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Querying servers " + _servers);
                        try {
                            lastFailed = !queryTime(_servers.toArray(new String[_servers.size()]));
                        } catch (IllegalArgumentException iae) {
                            if ( (!lastFailed) && (_log.shouldLog(Log.ERROR)) )
                                _log.error("Unable to reach any of the NTP servers - network disconnected?");
                            lastFailed = true;
                        }
                    }
                }
                
                _initialized = true;
                synchronized (this) { notifyAll(); }
                long sleepTime;
                if (lastFailed) {
                    sleepTime = 30*1000;
                } else {
                    sleepTime = _context.random().nextInt(_queryFrequency) + _queryFrequency;
                    if (_wellSynced)
                        sleepTime *= 3;
                }
                try { Thread.sleep(sleepTime); } catch (InterruptedException ie) {}
            }
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Timestamper died!", t);
            synchronized (this) { notifyAll(); }
        }
    }
    
    /**
     * True if the time was queried successfully, false if it couldn't be
     */
    private boolean queryTime(String serverList[]) throws IllegalArgumentException {
        long found[] = new long[_concurringServers];
        long now = -1;
        long expectedDelta = 0;
        _wellSynced = false;
        for (int i = 0; i < _concurringServers; i++) {
            try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
            now = NtpClient.currentTime(serverList);
            long delta = now - _context.clock().now();
            found[i] = delta;
            if (i == 0) {
                if (Math.abs(delta) < MAX_VARIANCE) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("a single SNTP query was within the tolerance (" + delta + "ms)");
                    // If less than a half second on the first try, we're in good shape
                    _wellSynced = Math.abs(delta) < 500;
                    break;
                } else {
                    // outside the tolerance, lets iterate across the concurring queries
                    expectedDelta = delta;
                }
            } else {
                if (Math.abs(delta - expectedDelta) > MAX_VARIANCE) {
                    if (_log.shouldLog(Log.ERROR)) {
                        StringBuilder err = new StringBuilder(96);
                        err.append("SNTP client variance exceeded at query ").append(i);
                        err.append(".  expected = ");
                        err.append(expectedDelta);
                        err.append(", found = ");
                        err.append(delta);
                        err.append(" all deltas: ");
                        for (int j = 0; j < found.length; j++)
                            err.append(found[j]).append(' ');
                        _log.error(err.toString());
                    }
                    return false;
                }
            }
        }
        stampTime(now);
        if (_log.shouldLog(Log.DEBUG)) {
            StringBuilder buf = new StringBuilder(64);
            buf.append("Deltas: ");
            for (int i = 0; i < found.length; i++)
                buf.append(found[i]).append(' ');
            _log.debug(buf.toString());
        }
        return true;
    }
    
    /**
     * Send an HTTP request to a given URL specifying the current time 
     */
    private void stampTime(long now) {
        long before = _context.clock().now();
        synchronized (_listeners) {
            for (int i = 0; i < _listeners.size(); i++) {
                UpdateListener lsnr = (UpdateListener)_listeners.get(i);
                lsnr.setNow(now);
            }
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Stamped the time as " + now + " (delta=" + (now-before) + ")");
    }
 
    /**
     * Reload all the config elements from the appContext
     *
     */
    private void updateConfig() {
        String serverList = _context.getProperty(PROP_SERVER_LIST);
        if ( (serverList == null) || (serverList.trim().length() <= 0) ) {
            serverList = DEFAULT_SERVER_LIST;
            String country = _context.getProperty(PROP_IP_COUNTRY);
            if (country == null) {
                country = Locale.getDefault().getCountry();
                if (country != null)
                    country = country.toLowerCase();
            }
            if (country != null &&  country.length() > 0) {
                _priorityServers = new ArrayList(3);
                for (int i = 0; i < 3; i++)
                     _priorityServers.add(i + "." + country + ".pool.ntp.org");
            } else {
                _priorityServers = null;
            }
        } else {
            _priorityServers = null;
        }
        _servers.clear();
        StringTokenizer tok = new StringTokenizer(serverList, ", ");
        while (tok.hasMoreTokens()) {
            String val = (String)tok.nextToken();
            val = val.trim();
            if (val.length() > 0)
                _servers.add(val);
        }
        
        _queryFrequency = Math.max(MIN_QUERY_FREQUENCY,
                                   _context.getProperty(PROP_QUERY_FREQUENCY, DEFAULT_QUERY_FREQUENCY));
        
        String disabled = _context.getProperty(PROP_DISABLED, DEFAULT_DISABLED);
        _disabled = Boolean.valueOf(disabled).booleanValue();
        
        _concurringServers = Math.min(4, Math.max(1,
                              _context.getProperty(PROP_CONCURRING_SERVERS, DEFAULT_CONCURRING_SERVERS)));
    }
    
    public static void main(String args[]) {
        System.setProperty(PROP_DISABLED, "false");
        System.setProperty(PROP_QUERY_FREQUENCY, "30000");
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        long now = ctx.clock().now();
        for (int i = 0; i < 5*60*1000; i += 61*1000) {
            try { Thread.sleep(61*1000); } catch (InterruptedException ie) {}
        }
    }
    
    /**
     * Interface to receive update notifications for when we query the time
     *
     */
    public interface UpdateListener {
        /**
         * The time has been queried and we have a current value for 'now'
         *
         */
        public void setNow(long now);
    }
}
