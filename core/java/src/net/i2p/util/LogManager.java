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
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

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
    public final static String PROP_DEFAULTLEVEL = "logger.defaultLevel";
    public final static String PROP_RECORD_PREFIX = "logger.record.";

    public final static String DEFAULT_FORMAT = DATE + " " + PRIORITY + " [" + THREAD + "] " + CLASS + ": " + MESSAGE;
    public final static String DEFAULT_DATEFORMAT = "HH:mm:ss.SSS";
    public final static String DEFAULT_FILENAME = "logs/log-#.txt";
    public final static String DEFAULT_FILESIZE = "10m";
    public final static boolean DEFAULT_DISPLAYONSCREEN = true;
    public final static int DEFAULT_CONSOLEBUFFERSIZE = 20;
    public final static String DEFAULT_ROTATIONLIMIT = "2";
    public final static String DEFAULT_DEFAULTLEVEL = Log.STR_ERROR;
    public final static String DEFAULT_ONSCREENLEVEL = Log.STR_CRIT;

    private I2PAppContext _context;
    private Log _log;
    
    /** when was the config file last read (or -1 if never) */
    private long _configLastRead;

    /** the config file */
    private File _locationFile;
    /** Ordered list of LogRecord elements that have not been written out yet */
    private List _records;
    /** List of explicit overrides of log levels (LogLimit objects) */
    private List _limits;
    /** String (scope) to Log object */
    private Map _logs;
    /** who clears and writes our records */
    private LogWriter _writer;

    /** 
     * default log level for logs that aren't explicitly controlled 
     * through a LogLimit in _limits
     */
    private int _defaultLimit;
    /** Log record format string */
    private char[] _format;
    /** Date format instance */
    private SimpleDateFormat _dateFormat;
    /** Date format string (for the SimpleDateFormat instance) */
    private String _dateFormatPattern;
    /** log filename pattern */
    private String _baseLogfilename;
    /** max # bytes in the logfile before rotation */
    private int _fileSize;
    /** max # rotated logs */
    private int _rotationLimit;
    /** minimum log level to be displayed on stdout */
    private int _onScreenLimit;

    /** whether or not we even want to display anything on stdout */
    private boolean _displayOnScreen;
    /** how many records we want to buffer in the "recent logs" list */
    private int _consoleBufferSize;
    /** the actual "recent logs" list */
    private LogConsoleBuffer _consoleBuffer;
    
    private boolean _alreadyNoticedMissingConfig;

    public LogManager(I2PAppContext context) {
        _displayOnScreen = true;
        _alreadyNoticedMissingConfig = false;
        _records = new ArrayList();
        _limits = new ArrayList(128);
        _logs = new HashMap(128);
        _defaultLimit = Log.ERROR;
        _configLastRead = 0;
        _context = context;
        _log = getLog(LogManager.class);
        String location = context.getProperty(CONFIG_LOCATION_PROP, CONFIG_LOCATION_DEFAULT);
        setConfig(location);
        _consoleBuffer = new LogConsoleBuffer(context);
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

    private LogManager() { // nop
    }
    
    public Log getLog(Class cls) { return getLog(cls, null); }
    public Log getLog(String name) { return getLog(null, name); }
    public Log getLog(Class cls, String name) {
        Log rv = null;
        String scope = Log.getScope(name, cls);
        boolean isNew = false;
        synchronized (_logs) {
            rv = (Log)_logs.get(scope);
            if (rv == null) {
                rv = new Log(this, cls, name);
                _logs.put(scope, rv);
                isNew = true;
            }
        }
        if (isNew)
            updateLimit(rv);
        return rv;
    }
    public List getLogs() {
        List rv = null;
        synchronized (_logs) {
            rv = new ArrayList(_logs.values());
        }
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
        _locationFile = new File(filename);
        if (!_locationFile.isAbsolute())
            _locationFile = new File(_context.getConfigDir(), filename);
        loadConfig();
    }

    public String currentFile() {
        return _writer.currentFile();
    }

    /**
     * Used by Log to add records to the queue
     *
     */
    void addRecord(LogRecord record) {
        int numRecords = 0;
        synchronized (_records) {
            _records.add(record);
            numRecords = _records.size();
        }
        
        if (numRecords > 100) {
            // the writer waits 10 seconds *or* until we tell them to wake up
            // before rereading the config and writing out any log messages
            synchronized (_writer) {
                _writer.notifyAll();
            }
        }
    }
    
    /**
     * Called periodically by the log writer's thread
     *
     */
    void rereadConfig() {
        // perhaps check modification time
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Rereading configuration file");
        loadConfig();
    }

    private void loadConfig() {
        File cfgFile = _locationFile;
        if (!cfgFile.exists()) {
            if (!_alreadyNoticedMissingConfig) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Log file " + _locationFile.getAbsolutePath() + " does not exist");
                _alreadyNoticedMissingConfig = true;
            }
            parseConfig(new Properties());
            updateLimits();
            return;
        }
        _alreadyNoticedMissingConfig = false;
        
        if ((_configLastRead > 0) && (_configLastRead >= cfgFile.lastModified())) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Short circuiting config read (last read: " 
                           + (_context.clock().now() - _configLastRead) + "ms ago, config file modified "
                           + (_context.clock().now() - cfgFile.lastModified()) + "ms ago");
            return;
        }

        Properties p = new Properties();
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(cfgFile);
            p.load(fis);
            _configLastRead = _context.clock().now();
        } catch (IOException ioe) {
            System.err.println("Error loading logger config from " + cfgFile.getAbsolutePath());
        } finally {
            if (fis != null) try {
                fis.close();
            } catch (IOException ioe) { // nop
            }
        }
        parseConfig(p);
        updateLimits();
    }

    private void parseConfig(Properties config) {
        String fmt = config.getProperty(PROP_FORMAT, DEFAULT_FORMAT);
        _format = fmt.toCharArray();
        
        String df = config.getProperty(PROP_DATEFORMAT, DEFAULT_DATEFORMAT);
        _dateFormatPattern = df;
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

        _fileSize = getFileSize(config.getProperty(PROP_FILESIZE, DEFAULT_FILESIZE));
        _rotationLimit = -1;
        try {
            String str = config.getProperty(PROP_ROTATIONLIMIT);
            _rotationLimit = Integer.parseInt(config.getProperty(PROP_ROTATIONLIMIT, DEFAULT_ROTATIONLIMIT));
        } catch (NumberFormatException nfe) {
            System.err.println("Invalid rotation limit");
            nfe.printStackTrace();
        }
        
        _defaultLimit = Log.getLevel(config.getProperty(PROP_DEFAULTLEVEL, DEFAULT_DEFAULTLEVEL));

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
        parseLimits(config, PROP_RECORD_PREFIX);
    }
    private void parseLimits(Properties config, String recordPrefix) {
        synchronized (_limits) {
            _limits.clear();
        }
        if (config != null) {
            for (Iterator iter = config.keySet().iterator(); iter.hasNext();) {
                String key = (String) iter.next();
                String val = config.getProperty(key);

                // if we're filtering the records (e.g. logger.record.*) then
                // filter accordingly (stripping off that prefix for matches)
                if (recordPrefix != null) {
                    if (key.startsWith(recordPrefix)) {
                        key = key.substring(recordPrefix.length());
                    } else {
                        continue;
                    }
                }

                LogLimit lim = new LogLimit(key, Log.getLevel(val));
                //_log.debug("Limit found for " + name + " as " + val);
                synchronized (_limits) {
                    if (!_limits.contains(lim))
                        _limits.add(lim);
                }
            }
        }
        updateLimits();
    }
    
    /**
     * Update the existing limit overrides
     *
     * @param limits mapping of prefix to log level string (not the log #) 
     */
    public void setLimits(Properties limits) {
        parseLimits(limits, null);
    }
    
    /**
     * Update the date format
     *
     * @return true if the format was updated, false if it was invalid
     */
    public boolean setDateFormat(String format) {
        if (format == null) return false;
        
        try {
            SimpleDateFormat fmt = new SimpleDateFormat(format);
            _dateFormatPattern = format;
            _dateFormat = fmt;
            return true;
        } catch (IllegalArgumentException iae) {
            getLog(LogManager.class).error("Date format is invalid [" + format + "]", iae);
            return false;
        }
    }
    
    /**
     * Update the log file size limit
     */
    public void setFileSize(int numBytes) {
        if (numBytes > 0)
            _fileSize = numBytes;
    }
    
    public String getDefaultLimit() { return Log.toLevelString(_defaultLimit); }
    public void setDefaultLimit(String lim) { 
        _defaultLimit = Log.getLevel(lim);
        updateLimits();
    }
    
    /**
     * Return a mapping of the explicit overrides - path prefix to (text 
     * formatted) limit.
     *
     */
    public Properties getLimits() {
        Properties rv = new Properties();
        synchronized (_limits) {
            for (int i = 0; i < _limits.size(); i++) {
                LogLimit lim = (LogLimit)_limits.get(i);
                rv.setProperty(lim.getRootName(), Log.toLevelString(lim.getLimit()));
            }
        }
        return rv;
    }

    /** 
     * Determine how many bytes are in the given formatted string (5m, 60g, 100k, etc)
     *
     */
    public int getFileSize(String size) {
        int sz = -1;
        try {
            String v = size;
            char mod = size.toUpperCase().charAt(size.length() - 1);
            if (!Character.isDigit(mod)) v = size.substring(0, size.length() - 1);
            int val = Integer.parseInt(v);
            switch (mod) {
                case 'K':
                    val *= 1024;
                    break;
                case 'M':
                    val *= 1024 * 1024;
                    break;
                case 'G':
                    val *= 1024 * 1024 * 1024;
                    break;
                default:
                    // blah, noop
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
        if (limits != null) {
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
        ArrayList limits = null; // new ArrayList(4);
        synchronized (_limits) {
            for (int i = 0; i < _limits.size(); i++) {
                LogLimit limit = (LogLimit)_limits.get(i);
                if (limit.matches(log)) { 
                    if (limits == null)
                        limits = new ArrayList(4);
                    limits.add(limit);
                }
            }
        }
        return limits;
    }

    ///
    /// would be friend methods for LogWriter...
    ///
    public String getBaseLogfilename() {
        return _baseLogfilename;
    }
    
    public void setBaseLogfilename(String filenamePattern) {
        _baseLogfilename = filenamePattern;
    }

    public int getFileSize() {
        return _fileSize;
    }

    public int getRotationLimit() {
        return _rotationLimit;
    }

    public boolean saveConfig() {
        String config = createConfig();
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(_locationFile);
            fos.write(config.getBytes());
            return true;
        } catch (IOException ioe) {
            getLog(LogManager.class).error("Error saving the config", ioe);
            return false;
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
    
    private String createConfig() {
        StringBuilder buf = new StringBuilder(8*1024);
        buf.append(PROP_FORMAT).append('=').append(new String(_format)).append('\n');
        buf.append(PROP_DATEFORMAT).append('=').append(_dateFormatPattern).append('\n');
        buf.append(PROP_DISPLAYONSCREEN).append('=').append((_displayOnScreen ? "TRUE" : "FALSE")).append('\n');
        String filenameOverride = _context.getProperty(FILENAME_OVERRIDE_PROP);
        if (filenameOverride == null)
            buf.append(PROP_FILENAME).append('=').append(_baseLogfilename).append('\n');
        else // this isn't technically correct - this could mess with some funky scenarios
            buf.append(PROP_FILENAME).append('=').append(DEFAULT_FILENAME).append('\n');
        
        if (_fileSize >= 1024*1024)
            buf.append(PROP_FILESIZE).append('=').append( (_fileSize / (1024*1024))).append("m\n");
        else if (_fileSize >= 1024)
            buf.append(PROP_FILESIZE).append('=').append( (_fileSize / (1024))).append("k\n");
        else if (_fileSize > 0)
            buf.append(PROP_FILESIZE).append('=').append(_fileSize).append('\n');
        // if <= 0, dont specify
        
        buf.append(PROP_ROTATIONLIMIT).append('=').append(_rotationLimit).append('\n');
        buf.append(PROP_DEFAULTLEVEL).append('=').append(Log.toLevelString(_defaultLimit)).append('\n');
        buf.append(PROP_DISPLAYONSCREENLEVEL).append('=').append(Log.toLevelString(_onScreenLimit)).append('\n');
        buf.append(PROP_CONSOLEBUFFERSIZE).append('=').append(_consoleBufferSize).append('\n');

        buf.append("# log limit overrides:\n");
        
        TreeMap limits = new TreeMap();
        synchronized (_limits) {
            for (int i = 0; i < _limits.size(); i++) {
                LogLimit lim = (LogLimit)_limits.get(i);
                limits.put(lim.getRootName(), Log.toLevelString(lim.getLimit()));
            }
        }
        for (Iterator iter = limits.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry entry = (Map.Entry)iter.next();
            String path = (String)entry.getKey();
            String lim = (String)entry.getValue();
            buf.append(PROP_RECORD_PREFIX).append(path);
            buf.append('=').append(lim).append('\n');
        }
        
        return buf.toString();
    }

    
    //List _getRecords() { return _records; }
    List _removeAll() {
        List vals = null;
        synchronized (_records) {
            if (_records.size() <= 0) 
                return null;
            vals = new ArrayList(_records);
            _records.clear();
        }
        return vals;
    }

    public char[] getFormat() {
        return _format;
    }
    
    public void setFormat(char fmt[]) {
        _format = fmt;
    }

    public SimpleDateFormat getDateFormat() {
        return _dateFormat;
    }
    public String getDateFormatPattern() {
        return _dateFormatPattern;
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
        } catch (Throwable t) { // nop
        }
        System.exit(0);
    }

    public void shutdown() {
        _log.log(Log.WARN, "Shutting down logger");
        _writer.flushRecords(false);
    }

    private static int __id = 0;
    private class ShutdownHook extends Thread {
        private int _id;
        public ShutdownHook() {
            _id = ++__id;
        }
        @Override
        public void run() {
            setName("Log " + _id + " shutdown ");
            shutdown();
        }
    }
}
