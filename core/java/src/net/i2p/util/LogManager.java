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
    public static final LogManager getInstance() { return _instance; }
    private static final LogManager _instance = new LogManager(System.getProperty(CONFIG_LOCATION_PROP, CONFIG_LOCATION_DEFAULT));
    private static final Log _log = new Log(LogManager.class);

    /**
     * These define the characters in the format line of the config file 
     */
    public static final char DATE       = 'd',
                             CLASS      = 'c',
                             THREAD     = 't',
                             PRIORITY   = 'p',
                             MESSAGE    = 'm';
    
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

    private long _configLastRead;

    private String _location;
    private List _records;
    private Set _limits;
    private Set _logs;
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
 
    public void setDisplayOnScreen(boolean yes) { _displayOnScreen = yes; }
    public boolean displayOnScreen() { return _displayOnScreen; }
    public int getDisplayOnScreenLevel() { return _onScreenLimit; }
    public void setDisplayOnScreenLevel(int level) { _onScreenLimit = level; }
    public int getConsoleBufferSize() { return _consoleBufferSize; }
    public void setConsoleBufferSize(int numRecords) { _consoleBufferSize = numRecords; }
    
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
     * Called during Log construction
     *
     */
    void registerLog(Log log) {
	synchronized (_logs) {
	    _logs.add(log);
	}
	updateLimit(log);
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

    private LogManager(String location) {
	_displayOnScreen = true;
	_location = location;
	_records = new ArrayList();
	_limits = new HashSet();
	_logs = new HashSet();
	_defaultLimit = Log.DEBUG;
	_configLastRead = 0;
	loadConfig();
	_writer = new LogWriter();
	Thread t = new I2PThread(_writer);
	t.setName("LogWriter");
	t.setDaemon(true);
	t.start();
	Runtime.getRuntime().addShutdownHook(new ShutdownHook());
    }
   
    //
    //
    //
    
    private void loadConfig() {
	Properties p = new Properties();
	File cfgFile = new File(_location);
	if ( (_configLastRead > 0) && (_configLastRead > cfgFile.lastModified()) ) {
	    _log.debug("Short circuiting config read");
	    return;
	}
	FileInputStream fis = null;
	try {
	    fis = new FileInputStream(cfgFile);
	    p.load(fis);
	    _configLastRead = cfgFile.lastModified();
	} catch (IOException ioe) {
	    System.err.println("Error loading logger config from " + new File(_location).getAbsolutePath());
	} finally {
	    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
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
	
	String filenameOverride = System.getProperty(FILENAME_OVERRIDE_PROP);
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
	
	parseLimits(config);
    }
    
    private void parseLimits(Properties config) {
	synchronized (_limits) {
	    _limits.clear();
	}
	for (Iterator iter = config.keySet().iterator(); iter.hasNext(); ) {
	    String key = (String)iter.next();
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
	    char mod = size.toUpperCase().charAt(size.length()-1);
	    if (!Character.isDigit(mod)) 
		v = size.substring(0, size.length()-1);
	    int val = Integer.parseInt(v);
	    switch ((int)mod) {
		case 'K':
		    val *= 1024;
		    break;
		case 'M':
		    val *= 1024*1024;
		    break;
		case 'G':
		    val *= 1024*1024*1024;
		    break;
		case 'T': // because we can
		    val *= 1024*1024*1024*1024;
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
	Set logs = new HashSet();
	synchronized (_logs) {
	    logs.addAll(_logs);
	}
	for (Iterator iter = logs.iterator(); iter.hasNext();) {
	    Log log = (Log)iter.next();
	    updateLimit(log);
	}
    }
    
    private void updateLimit(Log log) {
	List limits = getLimits(log);
	LogLimit max = null;
	LogLimit notMax = null;
	for (int i = 0; i < limits.size(); i++) {
	    LogLimit cur = (LogLimit)limits.get(i);
	    if (max == null) 
		max = cur;
	    else {
		if (cur.getRootName().length() > max.getRootName().length()) {
		    notMax = max;
		    max = cur;
		}
	    }
	}
	if (max != null)
	    log.setMinimumPriority(max.getLimit());
	else
	    log.setMinimumPriority(_defaultLimit);
    }
    
    private List getLimits(Log log) {
	ArrayList limits = new ArrayList();
	synchronized (_limits) {
	    for (Iterator iter = _limits.iterator(); iter.hasNext(); ) {
		LogLimit limit = (LogLimit)iter.next();
		if (limit.matches(log))
		    limits.add(limit);
	    }
	}
	return limits;
    }
    
    ///
    /// would be friend methods for LogWriter...
    ///
    String _getBaseLogfilename() { return _baseLogfilename; }
    int _getFileSize() { return _fileSize; }
    int _getRotationLimit() { return _rotationLimit; }
    //List _getRecords() { return _records; }
    List _removeAll() {
	List vals = null;
	synchronized (_records) {
	    vals = new ArrayList(_records);
	    _records.clear();
	}
	return vals;
    }
    char[] _getFormat() { return _format; }
    SimpleDateFormat _getDateFormat() { return _dateFormat; }
    
    public static void main(String args[]) {
	Log l1 = new Log("test.1");
	Log l2 = new Log("test.2");
	Log l21 = new Log("test.2.1");
	Log l = new Log("test");
	l.debug("this should fail");
	l.info("this should pass");
	l1.warn("this should pass");
	l1.info("this should fail");
	l2.error("this should fail");
	l21.debug("this should pass");
	l1.error("test exception", new Exception("test"));
	l1.error("test exception", new Exception("test"));
	try { Thread.sleep(2*1000); } catch (Throwable t) {}
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
