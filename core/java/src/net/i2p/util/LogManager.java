package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

import net.i2p.I2PAppContext;

/**
 * Manages the logging system, loading (and reloading) the configuration file,
 * coordinating the log limits, and storing the set of log records pending.
 * This also fires off a LogWriter thread that pulls pending records off and 
 * writes them where appropriate.
 * 
 */
public class LogManager {
    public final static String CONFIG_LOCATION_PROP = "loggerConfigLocation";
    public final static String FILENAME_OVERRIDE_PROP = "loggerFilenameOverride";
    public final static String CONFIG_LOCATION_DEFAULT = "logger.config";
    /**
     * These define the characters in the format line of the config file 
     */
    public static final char DATE = 'd', CLASS = 'c', THREAD = 't', PRIORITY = 'p', MESSAGE = 'm';

    public final static String PROP_FORMAT = "logger.format";
    public final static String PROP_DATEFORMAT = "logger.dateFormat";
    public final static String PROP_FILENAME = "logger.logFileName";
    public final static String PROP_FILESIZE = "logger.logFileSize";
    public final static String PROP_ROTATIONLIMIT = "logger.logRotationLimit";
    public final static String PROP_DISPLAYONSCREEN = "logger.displayOnScreen";
    public final static String PROP_CONSOLEBUFFERSIZE = "logger.consoleBufferSize";
    public final static String PROP_DISPLAYONSCREENLEVEL = "logger.minimumOnScreenLevel";
    public final static String PROP_DEFALTLEVEL = "logger.defaultLevel";
    public final static String PROP_RECORD_PREFIX = "logger.record.";

    public final static String DEFAULT_FORMAT = DATE + ' ' + PRIORITY + " [" + THREAD + "] " + CLASS + ": " + MESSAGE;
    public final static String DEFAULT_DATEFORMAT = "hh:mm:ss.SSS";
    public final static String DEFAULT_FILENAME = "log-#.txt";
    public final static String DEFAULT_FILESIZE = "10m";
    public final static boolean DEFAULT_DISPLAYONSCREEN = true;
    public final static int DEFAULT_CONSOLEBUFFERSIZE = 20;
    public final static String DEFAULT_ROTATIONLIMIT = "2";
    public final static String DEFAULT_DEFALTLEVEL = Log.STR_DEBUG;
    public final static String DEFAULT_ONSCREENLEVEL = Log.STR_DEBUG;

    private I2PAppContext _context;
    private Log _log;
    
    private long _configLastRead;

    private String _location;
    private List _records;
    private Set _limits;
    private Map _logs;
    private LogWriter _writer;

    private int _defaultLimit;
    private char[] _format;
    private SimpleDateFormat _dateFormat;
    private String _baseLogfilename;
    private int _fileSize;
    private int _rotationLimit;
    private int _onScreenLimit;

    private boolean _displayOnScreen;
    private int _consoleBufferSize;
    
    private LogConsoleBuffer _consoleBuffer;

    public LogManager(I2PAppContext context) {
        _displayOnScreen = true;
        _records = new ArrayList();
        _limits = new HashSet();
        _logs = new HashMap(128);
        _defaultLimit = Log.DEBUG;
        _configLastRead = 0;
        _location = context.getProperty(CONFIG_LOCATION_PROP, CONFIG_LOCATION_DEFAULT);
        _context = context;
        _log = getLog(LogManager.class);
        _consoleBuffer = new LogConsoleBuffer(context);
        loadConfig();
        _writer = new LogWriter(this);
        Thread t = new I2PThread(_writer);
        t.setName("LogWriter");
        t.setDaemon(true);
        t.start();
        try {
            Runtime.getRuntime().addShutdownHook(new ShutdownHook());
        } catch (IllegalStateException ise) {
            // shutdown in progress, fsck it
        }
        //System.out.println("Created logManager " + this + " with context: " + context);
    }
    private LogManager() {}
    
    public Log getLog(Class cls) { return getLog(cls, null); }
    public Log getLog(String name) { return getLog(null, name); }
    public Log getLog(Class cls, String name) {
        Log rv = null;
        synchronized (_logs) {
            Log newLog = new Log(this, cls, name);
            if (_logs.containsKey(newLog.getScope())) {
                Log oldLog = (Log)_logs.get(newLog.getScope());
                rv = oldLog;
                //_log.error("Duplicate log creation for " + cls);
            } else {
                _logs.put(newLog.getScope(), newLog);
                rv = newLog;
            }
        }
        updateLimit(rv);
        return rv;
    }
    void addLog(Log log) {
        synchronized (_logs) {
            if (!_logs.containsKey(log.getScope()))
                _logs.put(log.getScope(), log);
        }
        updateLimit(log);
    }
    
    public LogConsoleBuffer getBuffer() { return _consoleBuffer; }
        
    public void setDisplayOnScreen(boolean yes) {
        _displayOnScreen = yes;
    }

    public boolean displayOnScreen() {
        return _displayOnScreen;
    }

    public int getDisplayOnScreenLevel() {
        return _onScreenLimit;
    }

    public void setDisplayOnScreenLevel(int level) {
        _onScreenLimit = level;
    }

    public int getConsoleBufferSize() {
        return _consoleBufferSize;
    }

    public void setConsoleBufferSize(int numRecords) {
        _consoleBufferSize = numRecords;
    }

    public void setConfig(String filename) {
        _log.debug("Config filename set to " + filename);
        _location = filename;
        loadConfig();
    }

    /**
     * Used by Log to add records to the queue
     *
     */
    void addRecord(LogRecord record) {
        synchronized (_records) {
            _records.add(record);
        }
    }
    
    /**
     * Called periodically by the log writer's thread
     *
     */
    void rereadConfig() {
        // perhaps check modification time
        _log.debug("Rereading configuration file");
        loadConfig();
    }

    ///
    ///

    //
    //
    //

    private void loadConfig() {
        Properties p = new Properties();
        File cfgFile = new File(_location);
        if ((_configLastRead > 0) && (_configLastRead >= cfgFile.lastModified())) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Short circuiting config read");
            return;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Loading config from " + _location);
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(cfgFile);
            p.load(fis);
            _configLastRead = cfgFile.lastModified();
        } catch (IOException ioe) {
            System.err.println("Error loading logger config from " + new File(_location).getAbsolutePath());
        } finally {
            if (fis != null) try {
                fis.close();
            } catch (IOException ioe) {
            }
        }
        parseConfig(p);
        updateLimits();
    }

    private void parseConfig(Properties config) {
        String fmt = config.getProperty(PROP_FORMAT, new String(DEFAULT_FORMAT));
        _format = fmt.toCharArray();

        String df = config.getProperty(PROP_DATEFORMAT, DEFAULT_DATEFORMAT);
        _dateFormat = new SimpleDateFormat(df);

        String disp = config.getProperty(PROP_DISPLAYONSCREEN);
        if (disp == null)
            _displayOnScreen = DEFAULT_DISPLAYONSCREEN;
        else {
            if ("TRUE".equals(disp.toUpperCase().trim()))
                _displayOnScreen = true;
            else if ("YES".equals(disp.toUpperCase().trim()))
                _displayOnScreen = true;
            else
                _displayOnScreen = false;
        }

        String filenameOverride = _context.getProperty(FILENAME_OVERRIDE_PROP);
        if (filenameOverride != null)
            _baseLogfilename = filenameOverride;
        else
            _baseLogfilename = config.getProperty(PROP_FILENAME, DEFAULT_FILENAME);

        _fileSize = getFilesize(config.getProperty(PROP_FILESIZE, DEFAULT_FILESIZE));
        _rotationLimit = -1;
        try {
            String str = config.getProperty(PROP_ROTATIONLIMIT);
            if (str == null) System.err.println("Rotation limit not specified");
            _rotationLimit = Integer.parseInt(config.getProperty(PROP_ROTATIONLIMIT, DEFAULT_ROTATIONLIMIT));
        } catch (NumberFormatException nfe) {
            System.err.println("Invalid rotation limit");
            nfe.printStackTrace();
        }
        _defaultLimit = Log.getLevel(config.getProperty(PROP_DEFALTLEVEL, DEFAULT_DEFALTLEVEL));

        _onScreenLimit = Log.getLevel(config.getProperty(PROP_DISPLAYONSCREENLEVEL, DEFAULT_ONSCREENLEVEL));

        try {
            String str = config.getProperty(PROP_CONSOLEBUFFERSIZE);
            if (str == null)
                _consoleBufferSize = DEFAULT_CONSOLEBUFFERSIZE;
            else
                _consoleBufferSize = Integer.parseInt(str);
        } catch (NumberFormatException nfe) {
            System.err.println("Invalid console buffer size");
            nfe.printStackTrace();
            _consoleBufferSize = DEFAULT_CONSOLEBUFFERSIZE;
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Log set to use the base log file as " + _baseLogfilename);
        
        parseLimits(config);
    }

    private void parseLimits(Properties config) {
        synchronized (_limits) {
            _limits.clear();
        }
        for (Iterator iter = config.keySet().iterator(); iter.hasNext();) {
            String key = (String) iter.next();
            String val = config.getProperty(key);
            if (key.startsWith(PROP_RECORD_PREFIX)) {
                String name = key.substring(PROP_RECORD_PREFIX.length());
                LogLimit lim = new LogLimit(name, Log.getLevel(val));
                //_log.debug("Limit found for " + name + " as " + val);
                synchronized (_limits) {
                    _limits.add(lim);
                }
            }
        }
        updateLimits();
    }

    private int getFilesize(String size) {
        int sz = -1;
        try {
            String v = size;
            char mod = size.toUpperCase().charAt(size.length() - 1);
            if (!Character.isDigit(mod)) v = size.substring(0, size.length() - 1);
            int val = Integer.parseInt(v);
            switch ((int) mod) {
            case 'K':
                val *= 1024;
                break;
            case 'M':
                val *= 1024 * 1024;
                break;
            case 'G':
                val *= 1024 * 1024 * 1024;
                break;
            case 'T':
                // because we can
                val *= 1024 * 1024 * 1024 * 1024;
                break;
            }
            return val;
        } catch (Throwable t) {
            System.err.println("Error parsing config for filesize: [" + size + "]");
            t.printStackTrace();
            return -1;
        }
    }

    private void updateLimits() {
        Map logs = null;
        synchronized (_logs) {
            logs = new HashMap(_logs);
        }
        for (Iterator iter = logs.values().iterator(); iter.hasNext();) {
            Log log = (Log) iter.next();
            updateLimit(log);
        }
    }

    private void updateLimit(Log log) {
        List limits = getLimits(log);
        LogLimit max = null;
        LogLimit notMax = null;
        for (int i = 0; i < limits.size(); i++) {
            LogLimit cur = (LogLimit) limits.get(i);
            if (max == null)
                max = cur;
            else {
                if (cur.getRootName().length() > max.getRootName().length()) {
                    notMax = max;
                    max = cur;
                }
            }
        }
        if (max != null) {
            log.setMinimumPriority(max.getLimit());
        } else {
            //if (_log != null)
            //    _log.debug("The log for " + log.getClass() + " has no matching limits");
            log.setMinimumPriority(_defaultLimit);
        }
    }

    private List getLimits(Log log) {
        ArrayList limits = new ArrayList();
        synchronized (_limits) {
            for (Iterator iter = _limits.iterator(); iter.hasNext();) {
                LogLimit limit = (LogLimit) iter.next();
                if (limit.matches(log)) limits.add(limit);
            }
        }
        return limits;
    }

    ///
    /// would be friend methods for LogWriter...
    ///
    String _getBaseLogfilename() {
        return _baseLogfilename;
    }

    int _getFileSize() {
        return _fileSize;
    }

    int _getRotationLimit() {
        return _rotationLimit;
    }

    //List _getRecords() { return _records; }
    List _removeAll() {
        List vals = null;
        synchronized (_records) {
            vals = new ArrayList(_records);
            _records.clear();
        }
        return vals;
    }

    char[] _getFormat() {
        return _format;
    }

    SimpleDateFormat _getDateFormat() {
        return _dateFormat;
    }

    public static void main(String args[]) {
        I2PAppContext ctx = new I2PAppContext();
        Log l1 = ctx.logManager().getLog("test.1");
        Log l2 = ctx.logManager().getLog("test.2");
        Log l21 = ctx.logManager().getLog("test.2.1");
        Log l = ctx.logManager().getLog("test");
        l.debug("this should fail");
        l.info("this should pass");
        l1.warn("this should pass");
        l1.info("this should fail");
        l2.error("this should fail");
        l21.debug("this should pass");
        l1.error("test exception", new Exception("test"));
        l1.error("test exception", new Exception("test"));
        try {
            Thread.sleep(2 * 1000);
        } catch (Throwable t) {
        }
        System.exit(0);
    }

    public void shutdown() {
        _log.log(Log.CRIT, "Shutting down logger", new Exception("Shutdown"));
        _writer.flushRecords();
    }

    private class ShutdownHook extends Thread {
        public void run() {
            shutdown();
        }
    }
}