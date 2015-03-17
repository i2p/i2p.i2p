package net.i2p.stat;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Note - if no filter is defined in stat.logFilters at startup, this class will not
 * be instantiated - see StatManager.
 */
public class BufferedStatLog implements StatLog {
    private final I2PAppContext _context;
    private final Log _log;
    private final StatEvent _events[];
    private int _eventNext;
    private int _lastWrite;
    /** flush stat events to disk after this many events (or 30s)*/
    private int _flushFrequency;
    private final List _statFilters;
    private String _lastFilters;
    private BufferedWriter _out;
    private String _outFile;
    /** short circuit for adding data, set to true if some filters are set, false if its empty (so we can skip the sync) */
    private volatile boolean _filtersSpecified;
    
    private static final int BUFFER_SIZE = 1024;
    private static final boolean DISABLE_LOGGING = false;
    
    public BufferedStatLog(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(BufferedStatLog.class);
        _events = new StatEvent[BUFFER_SIZE];
        if (DISABLE_LOGGING) return;
        for (int i = 0; i < BUFFER_SIZE; i++)
            _events[i] = new StatEvent();
        _eventNext = 0;
        _lastWrite = _events.length-1;
        _statFilters = new ArrayList(10);
        _flushFrequency = 500;
        updateFilters();
        I2PThread writer = new I2PThread(new StatLogWriter(), "StatLogWriter");
        writer.setDaemon(true);
        writer.start();
    }
    
    public void addData(String scope, String stat, long value, long duration) {
        if (DISABLE_LOGGING) return;
        if (!shouldLog(stat)) return;
        synchronized (_events) {
            _events[_eventNext].init(scope, stat, value, duration);
            _eventNext = (_eventNext + 1) % _events.length;
            
            if (_eventNext == _lastWrite)
                _lastWrite = (_lastWrite + 1) % _events.length; // drop an event
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("AddData next=" + _eventNext + " lastWrite=" + _lastWrite);
            
            if (_eventNext > _lastWrite) {
                if (_eventNext - _lastWrite >= _flushFrequency)
                    _events.notifyAll();
            } else {
                if (_events.length - 1 - _lastWrite + _eventNext >= _flushFrequency)
                    _events.notifyAll();
            }
        }
    }    

    private boolean shouldLog(String stat) {
        if (!_filtersSpecified) return false;
        synchronized (_statFilters) {
            return _statFilters.contains(stat) || _statFilters.contains("*");
        }
    }
    
    private void updateFilters() {
        String val = _context.getProperty(StatManager.PROP_STAT_FILTER);
        if (val != null) {
            if ( (_lastFilters != null) && (_lastFilters.equals(val)) ) {
                // noop
            } else {
                StringTokenizer tok = new StringTokenizer(val, ",");
                synchronized (_statFilters) {
                    _statFilters.clear();
                    while (tok.hasMoreTokens())
                        _statFilters.add(tok.nextToken().trim());
                    _filtersSpecified = !_statFilters.isEmpty();
                }
            }
            _lastFilters = val;
        } else {
            synchronized (_statFilters) {
                _statFilters.clear(); 
                _filtersSpecified = false;
            }
        }
        
        String filename = _context.getProperty(StatManager.PROP_STAT_FILE, StatManager.DEFAULT_STAT_FILE);
        File foo = new File(filename);
        if (!foo.isAbsolute())
            filename = (new File(_context.getRouterDir(), filename)).getAbsolutePath();
        if ( (_outFile != null) && (_outFile.equals(filename)) ) {
            // noop
        } else {
            if (_out != null) try { _out.close(); } catch (IOException ioe) {}
            _outFile = filename;
            try {
                _out = new BufferedWriter(new FileWriter(_outFile, true), 32*1024);
            } catch (IOException ioe) { ioe.printStackTrace(); }
        }
    }
    
    private class StatLogWriter implements Runnable {
        private final SimpleDateFormat _fmt = new SimpleDateFormat("yyyyMMdd HH:mm:ss.SSS");
        public void run() {
            int writeStart = -1;
            int writeEnd = -1;
            while (true) {
                try {
                    synchronized (_events) {
                        if (_eventNext > _lastWrite) {
                            if (_eventNext - _lastWrite < _flushFrequency)
                                _events.wait(30*1000);
                        } else {
                            if (_events.length - 1 - _lastWrite + _eventNext < _flushFrequency)
                                _events.wait(30*1000);
                        }
                        writeStart = (_lastWrite + 1) % _events.length;
                        writeEnd = _eventNext;
                        _lastWrite = (writeEnd == 0 ? _events.length-1 : writeEnd - 1);
                    }
                    if (writeStart != writeEnd) {
                        try {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("writing " + writeStart +"->"+ writeEnd);
                            writeEvents(writeStart, writeEnd);
                        } catch (Exception e) {
                            _log.error("error writing " + writeStart +"->"+ writeEnd, e);
                        }
                    }
                } catch (InterruptedException ie) {}
            }
        }
        
        private void writeEvents(int start, int end) {
            try {    
                updateFilters();
                int cur = start;
                while (cur != end) {
                    //if (shouldLog(_events[cur].getStat())) {
                        String when = null;
                        synchronized (_fmt) {
                            when = _fmt.format(new Date(_events[cur].getTime()));
                        }
                        _out.write(when);
                        _out.write(" ");
                        if (_events[cur].getScope() == null)
                            _out.write("noScope");
                        else
                            _out.write(_events[cur].getScope());
                        _out.write(" ");
                        _out.write(_events[cur].getStat());
                        _out.write(" ");
                        _out.write(Long.toString(_events[cur].getValue()));
                        _out.write(" ");
                        _out.write(Long.toString(_events[cur].getDuration()));
                        _out.write("\n");
                    //}
                    cur = (cur + 1) % _events.length;
                }
                _out.flush();
            } catch (IOException ioe) {
                _log.error("Error writing out", ioe);
            }
        }
    }
    
    private class StatEvent {
        private long _time;
        private String _scope;
        private String _stat;
        private long _value;
        private long _duration;
        
        public long getTime() { return _time; } 
        public String getScope() { return _scope; }
        public String getStat() { return _stat; }
        public long getValue() { return _value; }
        public long getDuration() { return _duration; }
        
        public void init(String scope, String stat, long value, long duration) {
            _scope = scope;
            _stat = stat;
            _value = value;
            _duration = duration;
            _time = _context.clock().now();
        }
    }    
}
