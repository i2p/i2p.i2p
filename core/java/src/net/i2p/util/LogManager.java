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
import java.io.Flushable;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

/**
 * Manages the logging system, loading (and reloading) the configuration file,
 * coordinating the log limits, and storing the set of log records pending.
 * This also fires off a LogWriter thread that pulls pending records off and 
 * writes them where appropriate.
 * 
 */
public class LogManager implements Flushable {
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
    /** @since 0.9.2 */
    private static final String PROP_LOG_BUFFER_SIZE = "logger.logBufferSize";
    /** @since 0.9.2 */
    private static final String PROP_DROP = "logger.dropOnOverflow";
    /** @since 0.9.3 */
    private static final String PROP_DUP = "logger.dropDuplicates";
    /** @since 0.9.18 */
    private static final String PROP_FLUSH = "logger.flushInterval";
    public final static String PROP_RECORD_PREFIX = "logger.record.";

    public final static String DEFAULT_FORMAT = DATE + " " + PRIORITY + " [" + THREAD + "] " + CLASS + ": " + MESSAGE;
    //public final static String DEFAULT_DATEFORMAT = "HH:mm:ss.SSS";
    /** blank means default short date and medium time for the locale - see DateFormat */
    public final static String DEFAULT_DATEFORMAT = "";
    public final static String DEFAULT_FILENAME = "logs/log-@.txt";
    public final static String DEFAULT_FILESIZE = "10m";
    public final static boolean DEFAULT_DISPLAYONSCREEN = true;
    public final static int DEFAULT_CONSOLEBUFFERSIZE = 20;
    public final static String DEFAULT_ROTATIONLIMIT = "2";
    public final static String DEFAULT_DEFAULTLEVEL = Log.STR_ERROR;
    public final static String DEFAULT_ONSCREENLEVEL = Log.STR_CRIT;

    private final I2PAppContext _context;
    private final Log _log;
    
    /** when was the config file last read (or -1 if never) */
    private long _configLastRead;

    /** the config file */
    private File _locationFile;

    /** max to LogRecords to buffer in memory before we start blocking */
    private static final int MAX_BUFFER = 1024;
    /** Ordered list of LogRecord elements that have not been written out yet */
    private final LinkedBlockingQueue<LogRecord> _records;
    /** List of explicit overrides of log levels (LogLimit objects) */
    private final Set<LogLimit> _limits;
    /** String (scope) or Log.LogScope to Log object */
    private final ConcurrentHashMap<Object, Log> _logs;
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
    private int _consoleBufferSize = DEFAULT_CONSOLEBUFFERSIZE;
    /** the actual "recent logs" list */
    private final LogConsoleBuffer _consoleBuffer;
    private int _logBufferSize = MAX_BUFFER;
    private boolean _dropOnOverflow;
    private boolean _dropDuplicates;
    private final AtomicLong _droppedRecords = new AtomicLong();
    // in seconds
    private int _flushInterval = (int) (LogWriter.FLUSH_INTERVAL / 1000);
    
    private boolean _alreadyNoticedMissingConfig;

    public LogManager(I2PAppContext context) {
        _displayOnScreen = true;
        _alreadyNoticedMissingConfig = false;
        _limits = new ConcurrentHashSet<LogLimit>();
        _logs = new ConcurrentHashMap<Object, Log>(128);
        _defaultLimit = Log.ERROR;
        _context = context;
        _log = getLog(LogManager.class);
        String location = context.getProperty(CONFIG_LOCATION_PROP, CONFIG_LOCATION_DEFAULT);
        setConfig(location);
        _records = new LinkedBlockingQueue<LogRecord>(_logBufferSize);
        _consoleBuffer = new LogConsoleBuffer(_consoleBufferSize);
        // If we aren't in the router context, delay creating the LogWriter until required,
        // so it doesn't create a log directory and log files unless there is output.
        // In the router context, we have to rotate to a new log file at startup or the logs.jsp
        // page will display the old log.
        if (context.isRouterContext()) {
            startLogWriter();
        } else {
            // Only in App Context.
            // In Router Context, the router has its own shutdown hook,
            // and will call our shutdown() from Router.finalShutdown()
            try {
                Runtime.getRuntime().addShutdownHook(new ShutdownHook());
            } catch (IllegalStateException ise) {
                // shutdown in progress
            }
        }
    }

    /** @since 0.8.2 */
    private synchronized void startLogWriter() {
        // yeah, this doesn't always work, _writer should be volatile
        if (_writer != null)
            return;
        if (SystemVersion.isAndroid()) {
            try {
                Class<? extends LogWriter> clazz = Class.forName(
                        "net.i2p.util.AndroidLogWriter"
                    ).asSubclass(LogWriter.class);
                Constructor<? extends LogWriter> ctor = clazz.getDeclaredConstructor(LogManager.class);
                _writer = ctor.newInstance(this);
            } catch (ClassNotFoundException e) {
            } catch (InstantiationException e) {
            } catch (IllegalAccessException e) {
            } catch (InvocationTargetException e) {
            } catch (NoSuchMethodException e) {
            }
        }
        // Default writer
        if (_writer == null)
            _writer = new FileLogWriter(this);
        _writer.setFlushInterval(_flushInterval * 1000);
        // if you enable logging in I2PThread again, you MUST change this back to Thread
        Thread t = new I2PThread(_writer, "LogWriter");
        t.setDaemon(true);
        t.start();
    }

    public Log getLog(Class<?> cls) { return getLog(cls, null); }
    public Log getLog(String name) { return getLog(null, name); }
    public Log getLog(Class<?> cls, String name) {
        String scope = Log.getScope(name, cls);
        boolean isNew = false;
        Log rv = _logs.get(scope);
        if (rv == null) {
            rv = new Log(this, cls, name);
            Log old = _logs.putIfAbsent(scope, rv);
            isNew = old == null;
            if (!isNew)
                rv = old;
        }
        if (isNew)
            updateLimit(rv);
        return rv;
    }

    /** now used by ConfigLogingHelper */
    public List<Log> getLogs() {
        return new ArrayList<Log>(_logs.values());
    }

    /**
     *  If the log already exists, its priority is set here but cannot
     *  be changed later, as it becomes an "orphan" not tracked by the manager.
     */
    void addLog(Log log) {
        Log old = _logs.putIfAbsent(log.getScope(), log);
        updateLimit(log);
        if (old != null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Duplicate log for " + log.getName());
        }
    }
    
    public LogConsoleBuffer getBuffer() { return _consoleBuffer; }
        
    /** @deprecated unused */
    @Deprecated
    public void setDisplayOnScreen(boolean yes) {
        _displayOnScreen = yes;
    }

    public boolean displayOnScreen() {
        return _displayOnScreen;
    }

    public int getDisplayOnScreenLevel() {
        return _onScreenLimit;
    }

    /** @deprecated unused */
    @Deprecated
    public void setDisplayOnScreenLevel(int level) {
        _onScreenLimit = level;
    }

    public int getConsoleBufferSize() {
        return _consoleBufferSize;
    }

    /** @deprecated unused */
    @Deprecated
    public void setConsoleBufferSize(int numRecords) {
        _consoleBufferSize = numRecords;
    }

    public void setConfig(String filename) {
        _locationFile = new File(filename);
        if (!_locationFile.isAbsolute())
            _locationFile = new File(_context.getConfigDir(), filename);
        loadConfig();
    }

    /**
     *  File may not exist or have old logs in it if not opened yet
     *  @return non-null
     */
    public String currentFile() {
        if (_writer == null)
            return ("No log file created yet");
        return _writer.currentFile();
    }

    /**
     * Used by Log to add records to the queue.
     * This is generally nonblocking and unsyncrhonized but may block when under
     * massive logging load as a way of throttling logging threads.
     */
    void addRecord(LogRecord record) {
        if ((!_context.isRouterContext()) && _writer == null)
            startLogWriter();

        boolean success = _records.offer(record);
        if (!success) {
            if (_dropOnOverflow) {
                // TODO use the counter in a periodic drop msg
                _droppedRecords.incrementAndGet();
                return;
            }
            // the writer waits 10 seconds *or* until we tell them to wake up
            // before rereading the config and writing out any log messages
            synchronized (_writer) {
                _writer.notifyAll();
            }
            // block as a way of slowing down out-of-control loggers (a little)
            try {
                _records.put(record);
            } catch (InterruptedException ie) {}
        } else if (_flushInterval <= 0) {
            synchronized (_writer) {
                _writer.notifyAll();
            }
        }
    }
    
    /**
     * Called periodically by the log writer's thread
     * Do not log here, deadlock of LogWriter
     */
    void rereadConfig() {
        // perhaps check modification time
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Rereading configuration file");
        loadConfig();
    }

    /**
     *  @since 0.9.3
     */
    boolean shouldDropDuplicates() {
        return _dropDuplicates;
    }

    /**
     * Do not log here, deadlock of LogWriter via rereadConfig().
     */
    private void loadConfig() {
        File cfgFile = _locationFile;
        if (!cfgFile.exists()) {
            if (!_alreadyNoticedMissingConfig) {
                //if (_log.shouldLog(Log.WARN))
                //    _log.warn("Log file " + _locationFile.getAbsolutePath() + " does not exist");
                _alreadyNoticedMissingConfig = true;
            }
            parseConfig(new Properties());
            updateLimits();
            return;
        }
        _alreadyNoticedMissingConfig = false;
        
        if ((_configLastRead > 0) && (_configLastRead >= cfgFile.lastModified())) {
            //if (_log.shouldLog(Log.INFO))
            //    _log.info("Short circuiting config read (last read: " 
            //               + (_context.clock().now() - _configLastRead) + "ms ago, config file modified "
            //               + (_context.clock().now() - cfgFile.lastModified()) + "ms ago");
            return;
        }

        Properties p = new Properties();
        try {
            DataHelper.loadProps(p, cfgFile);
            _configLastRead = _context.clock().now();
        } catch (IOException ioe) {
            System.err.println("Error loading logger config from " + cfgFile.getAbsolutePath());
        }
        parseConfig(p);
        updateLimits();
    }

    /**
     * Do not log here, deadlock of LogWriter via rereadConfig().
     */
    private void parseConfig(Properties config) {
        String fmt = config.getProperty(PROP_FORMAT, DEFAULT_FORMAT);
        _format = fmt.toCharArray();
        
        String df = config.getProperty(PROP_DATEFORMAT, DEFAULT_DATEFORMAT);
        setDateFormat(df);

        String disp = config.getProperty(PROP_DISPLAYONSCREEN);
        if (disp == null)
            _displayOnScreen = DEFAULT_DISPLAYONSCREEN;
        else {
            if ("TRUE".equals(disp.toUpperCase(Locale.US).trim()))
                _displayOnScreen = true;
            else if ("YES".equals(disp.toUpperCase(Locale.US).trim()))
                _displayOnScreen = true;
            else
                _displayOnScreen = false;
        }

        // prior to 0.9.5, override prop trumped config file
        // as of 0.9.5, override prop trumps config file only if config file is set to default,
        // so it may be set in the UI.
        String filename = config.getProperty(PROP_FILENAME, DEFAULT_FILENAME);
        String filenameOverride = _context.getProperty(FILENAME_OVERRIDE_PROP);
        if (filenameOverride != null && filename.equals(DEFAULT_FILENAME))
            setBaseLogfilename(filenameOverride);
        else
            setBaseLogfilename(filename);

        _fileSize = getFileSize(config.getProperty(PROP_FILESIZE, DEFAULT_FILESIZE));

        _rotationLimit = -1;
        try {
            _rotationLimit = Integer.parseInt(config.getProperty(PROP_ROTATIONLIMIT, DEFAULT_ROTATIONLIMIT));
        } catch (NumberFormatException nfe) {
            System.err.println("Invalid rotation limit");
            nfe.printStackTrace();
        }
        
        _defaultLimit = Log.getLevel(config.getProperty(PROP_DEFAULTLEVEL, DEFAULT_DEFAULTLEVEL));

        _onScreenLimit = Log.getLevel(config.getProperty(PROP_DISPLAYONSCREENLEVEL, DEFAULT_ONSCREENLEVEL));

        try {
            String str = config.getProperty(PROP_CONSOLEBUFFERSIZE);
            if (str != null)
                _consoleBufferSize = Integer.parseInt(str);
        } catch (NumberFormatException nfe) {}

        try {
            String str = config.getProperty(PROP_LOG_BUFFER_SIZE);
            if (str != null)
                _logBufferSize = Integer.parseInt(str);
        } catch (NumberFormatException nfe) {}

        try {
            String str = config.getProperty(PROP_FLUSH);
            if (str != null) {
                _flushInterval = Integer.parseInt(str);
                synchronized(this) {
                    if (_writer != null)
                        _writer.setFlushInterval(_flushInterval * 1000);
                }
            }
        } catch (NumberFormatException nfe) {}

        _dropOnOverflow = Boolean.parseBoolean(config.getProperty(PROP_DROP));
        String str = config.getProperty(PROP_DUP);
        _dropDuplicates = str == null || Boolean.parseBoolean(str);

        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Log set to use the base log file as " + _baseLogfilename);
        
        parseLimits(config);
    }

    /**
     * Do not log here, deadlock of LogWriter via rereadConfig().
     */
    private void parseLimits(Properties config) {
        parseLimits(config, PROP_RECORD_PREFIX);
    }

    /**
     * Do not log here, deadlock of LogWriter via rereadConfig().
     */
    private void parseLimits(Properties config, String recordPrefix) {
        _limits.clear();
        if (config != null) {
            for (Map.Entry<Object, Object> e : config.entrySet()) {
                String key = (String) e.getKey();

                // if we're filtering the records (e.g. logger.record.*) then
                // filter accordingly (stripping off that prefix for matches)
                if (recordPrefix != null) {
                    if (key.startsWith(recordPrefix)) {
                        key = key.substring(recordPrefix.length());
                    } else {
                        continue;
                    }
                }

                String val = (String) e.getValue();
                LogLimit lim = new LogLimit(key, Log.getLevel(val));
                //_log.debug("Limit found for " + name + " as " + val);
                if (!_limits.contains(lim))
                    _limits.add(lim);
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
     * Do not log here, deadlock of LogWriter via rereadConfig().
     *
     * @param format null or empty string means use default format for the locale
     *               (with a SHORT date and a MEDIUM time - see DateFormat)
     * @return true if the format was updated, false if it was invalid
     */
    public boolean setDateFormat(String format) {
        if (format == null)
            format = "";
        if (format.equals(_dateFormatPattern) && _dateFormat != null)
            return true;
        
        try {
            SimpleDateFormat fmt = (SimpleDateFormat) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
            if (!format.equals(""))
                fmt.applyPattern(format);
            // the router sets the JVM time zone to UTC but saves the original here so we can get it
            fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
            _dateFormatPattern = format;
            _dateFormat = fmt;
            return true;
        } catch (IllegalArgumentException iae) {
            //getLog(LogManager.class).error("Date format is invalid [" + format + "]", iae);
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
        for (LogLimit lim : _limits) {
            rv.setProperty(lim.getRootName(), Log.toLevelString(lim.getLimit()));
        }
        return rv;
    }

    /** 
     * Determine how many bytes are in the given formatted string (5m, 60g, 100k, etc)
     * Size may be k, m, or g; a trailing b is ignored. Upper-case is allowed.
     * Spaces between the number and letter is are allowed.
     * The number may be in floating point.
     * 4096 min, 2 GB max (returns int)
     */
    public static int getFileSize(String size) {
        try {
            String v = size.trim().toUpperCase(Locale.US);
            if (v.length() < 2)
                return -1;
            if (v.endsWith("IB"))
                v = v.substring(0, v.length() - 2);
            else if (v.endsWith("B"))
                v = v.substring(0, v.length() - 1);
            char mod = v.charAt(v.length() - 1);
            if (!Character.isDigit(mod)) v = v.substring(0, v.length() - 1);
            // output to form was in current locale, so have to parse it back that way
            double val = (new DecimalFormat()).parse(v.trim()).doubleValue();
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
            if (val < 4096 || val > Integer.MAX_VALUE)
                return -1;
            return (int) val;
        } catch (Throwable t) {
            System.err.println("Error parsing config for filesize: [" + size + "]");
            return -1;
        }
    }

    /**
     * Do not log here, deadlock of LogWriter via rereadConfig().
     */
    private void updateLimits() {
        for (Log log : _logs.values()) {
            updateLimit(log);
        }
    }

    /**
     * Do not log here, deadlock of LogWriter via rereadConfig().
     */
    private void updateLimit(Log log) {
        List<LogLimit> limits = getLimits(log);
        LogLimit max = null;
        if (limits != null) {
            for (LogLimit cur : limits) {
                if (max == null)
                    max = cur;
                else {
                    if (cur.getRootName().length() > max.getRootName().length()) {
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

    /**
     * Do not log here, deadlock of LogWriter via rereadConfig().
     * @return null if no matches
     */
    private List<LogLimit> getLimits(Log log) {
        ArrayList<LogLimit> limits = null; // new ArrayList(4);
        for (LogLimit limit : _limits) {
            if (limit.matches(log)) { 
                if (limits == null)
                    limits = new ArrayList<LogLimit>(4);
                limits.add(limit);
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
        // '#' is a comment character in loadProps/storeProps
        _baseLogfilename = filenamePattern.replace('#', '@');
    }

    public int getFileSize() {
        return _fileSize;
    }

    public int getRotationLimit() {
        return _rotationLimit;
    }

    /** @return success */
    public boolean saveConfig() {
        Properties props = createConfig();
        try {
            DataHelper.storeProps(props, _locationFile);
            return true;
        } catch (IOException ioe) {
            getLog(LogManager.class).error("Error saving the config", ioe);
            return false;
        }
    }
    
    private Properties createConfig() {
        Properties rv = new OrderedProperties();
        rv.setProperty(PROP_FORMAT, new String(_format));
        rv.setProperty(PROP_DATEFORMAT, _dateFormatPattern);
        rv.setProperty(PROP_DISPLAYONSCREEN, Boolean.toString(_displayOnScreen));
        rv.setProperty(PROP_DROP, Boolean.toString(_dropOnOverflow));
        rv.setProperty(PROP_DUP, Boolean.toString(_dropDuplicates));
        rv.setProperty(PROP_LOG_BUFFER_SIZE, Integer.toString(_logBufferSize));

        // prior to 0.9.5, override prop trumped config file
        // as of 0.9.5, override prop trumps config file only if config file is set to default,
        // so it may be set in the UI.
        rv.setProperty(PROP_FILENAME, _baseLogfilename);
        
        if (_fileSize >= 1024*1024)
            rv.setProperty(PROP_FILESIZE,  (_fileSize / (1024*1024)) + "m");
        else if (_fileSize >= 1024)
            rv.setProperty(PROP_FILESIZE,  (_fileSize / (1024))+ "k");
        else if (_fileSize > 0)
            rv.setProperty(PROP_FILESIZE, Integer.toString(_fileSize));
        // if <= 0, dont specify
        
        rv.setProperty(PROP_ROTATIONLIMIT, Integer.toString(_rotationLimit));
        rv.setProperty(PROP_DEFAULTLEVEL, Log.toLevelString(_defaultLimit));
        rv.setProperty(PROP_DISPLAYONSCREENLEVEL, Log.toLevelString(_onScreenLimit));
        rv.setProperty(PROP_CONSOLEBUFFERSIZE, Integer.toString(_consoleBufferSize));
        rv.setProperty(PROP_FLUSH, Integer.toString(_flushInterval));

        for (LogLimit lim : _limits) {
            rv.setProperty(PROP_RECORD_PREFIX + lim.getRootName(), Log.toLevelString(lim.getLimit()));
        }
        
        return rv;
    }

    /**
     *  Zero-copy.
     *  For the LogWriter
     *  @since 0.8.2
     */
    Queue<LogRecord> getQueue() {
        return _records;
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

/*****
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
*****/

    /**
     *  Flush any pending records to disk.
     *  Blocking up to 250 ms.
     *  @since 0.9.3
     */
    public void flush() {
        if (_writer != null) {
            int i = 50;
            while ((!_records.isEmpty()) && i-- > 0) {
                synchronized (_writer) {
                    _writer.notifyAll();
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {}
            }
        }
    }

    public void shutdown() {
        if (_writer != null) {
            //_log.log(Log.WARN, "Shutting down logger");
            // try to prevent out-of-order logging at shutdown
            flush();
            // this could generate out-of-order messages
            _writer.flushRecords(false);
            _writer.stopWriting();
            synchronized (_writer) {
                _writer.notifyAll();
            }
        }
        _records.clear();
        _limits.clear();
        _logs.clear();
        _consoleBuffer.clear();
    }

    private static final AtomicInteger __id = new AtomicInteger();

    private class ShutdownHook extends I2PAppThread {
        private final int _id;
        public ShutdownHook() {
            _id = __id.incrementAndGet();
        }
        @Override
        public void run() {
            setName("Log " + _id + " shutdown ");
            shutdown();
        }
    }

    /**
     *  Convenience method for LogRecordFormatter
     *  @since 0.7.14
     */
    I2PAppContext getContext() {
        return _context;
    }
}
