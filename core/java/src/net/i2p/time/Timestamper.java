package net.i2p.time;

import java.util.ArrayList;
import java.util.List;
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
    private List _servers;
    private List _listeners;
    private int _queryFrequency;
    private boolean _disabled;
    
    private static final int DEFAULT_QUERY_FREQUENCY = 5*60*1000;
    private static final String DEFAULT_SERVER_LIST = "pool.ntp.org, pool.ntp.org";
    private static final boolean DEFAULT_DISABLED = false;
    
    public static final String PROP_QUERY_FREQUENCY = "time.queryFrequencyMs";
    public static final String PROP_SERVER_LIST = "time.sntpServerList";
    public static final String PROP_DISABLED = "time.disabled";
    
    public Timestamper(I2PAppContext ctx) {
        this(ctx, null);
    }
    
    public Timestamper(I2PAppContext ctx, UpdateListener lsnr) {
        _context = ctx;
        _servers = new ArrayList(1);
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
        t.start();
    }
    
    public void run() {
        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
        _log = _context.logManager().getLog(Timestamper.class);
        if (_log.shouldLog(Log.INFO))
            _log.info("Starting timestamper");

        if (_log.shouldLog(Log.INFO))
            _log.info("Starting up timestamper");
        try {
            while (true) {
                if (!_disabled) {
                    String serverList[] = null;
                    synchronized (_servers) {
                        serverList = new String[_servers.size()];
                        for (int i = 0; i < serverList.length; i++)
                            serverList[i] = (String)_servers.get(i);
                    }
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Querying servers " + _servers);
                    try {
                        long now = NtpClient.currentTime(serverList);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Stamp time");
                        stampTime(now);
                    } catch (IllegalArgumentException iae) {
                        _log.log(Log.CRIT, "Unable to reach any of the NTP servers - network disconnected?");
                    }
                }
                updateConfig();
                try { Thread.sleep(_queryFrequency); } catch (InterruptedException ie) {}
            }
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Timestamper died!", t);
        }
    }
    
    /**
     * Send an HTTP request to a given URL specifying the current time 
     */
    private void stampTime(long now) {
        synchronized (_listeners) {
            for (int i = 0; i < _listeners.size(); i++) {
                UpdateListener lsnr = (UpdateListener)_listeners.get(i);
                lsnr.setNow(now);
            }
        }
    }
 
    /**
     * Reload all the config elements from the appContext
     *
     */
    private void updateConfig() {
        String serverList = _context.getProperty(PROP_SERVER_LIST);
        if ( (serverList == null) || (serverList.trim().length() <= 0) )
            serverList = DEFAULT_SERVER_LIST;
        synchronized (_servers) {
            _servers.clear();
            StringTokenizer tok = new StringTokenizer(serverList, ",");
            while (tok.hasMoreTokens()) {
                String val = (String)tok.nextToken();
                val = val.trim();
                if (val.length() > 0)
                    _servers.add(val);
            }
        }
        
        String freq = _context.getProperty(PROP_QUERY_FREQUENCY);
        if ( (freq == null) || (freq.trim().length() <= 0) )
            freq = DEFAULT_QUERY_FREQUENCY + "";
        try {
            int ms = Integer.parseInt(freq);
            if (ms > 60*1000) {
                _queryFrequency = ms;
            } else {
                if ( (_log != null) && (_log.shouldLog(Log.ERROR)) )
                    _log.error("Query frequency once every " + ms + "ms is too fast!");
                _queryFrequency = DEFAULT_QUERY_FREQUENCY;
            }
        } catch (NumberFormatException nfe) {
            if ( (_log != null) && (_log.shouldLog(Log.WARN)) )
                _log.warn("Invalid query frequency [" + freq + "], falling back on " + DEFAULT_QUERY_FREQUENCY);
            _queryFrequency = DEFAULT_QUERY_FREQUENCY;
        }
        
        String disabled = _context.getProperty(PROP_DISABLED);
        if (disabled == null)
            disabled = DEFAULT_DISABLED + "";
        _disabled = Boolean.getBoolean(disabled);
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