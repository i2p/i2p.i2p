package net.i2p.stat;

import java.io.BufferedWriter;
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
 * 
 */
public class BufferedStatLog implements StatLog {
    private I2PAppContext _context;
    private Log _log;
    private StatEvent _events[];
    private int _eventNext;
    private int _lastWrite;
    /** flush stat events to disk after this many events (or 30s)*/
    private int _flushFrequency;
    private List _statFilters;
    private String _lastFilters;
    private BufferedWriter _out;
    private String _outFile;
    
    public BufferedStatLog(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(BufferedStatLog.class);
        _events = new StatEvent[1000];
        for (int i = 0; i < 1000; i++)
            _events[i] = new StatEvent();
        _eventNext = 0;
        _lastWrite = _events.length-1;
        _statFilters = new ArrayList(10);
        _flushFrequency = 500;
        I2PThread writer = new I2PThread(new StatLogWriter(), "StatLogWriter");
        writer.setDaemon(true);
        writer.start();
    }
    
    public void addData(String scope, String stat, long value, long duration) {
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
        synchronized (_statFilters) {
            return _statFilters.contains(stat);
        }
    }

    private void updateFilters() {
        String val = _context.getProperty("stat.logFilters");
        if (val != null) {
            if ( (_lastFilters != null) && (_lastFilters.equals(val)) ) {
                // noop
            } else {
                StringTokenizer tok = new StringTokenizer(val, ",");
                synchronized (_statFilters) {
                    _statFilters.clear();
                    while (tok.hasMoreTokens())
                        _statFilters.add(tok.nextToken().trim());
                }
            }
            _lastFilters = val;
        } else {
            synchronized (_statFilters) { _statFilters.clear(); }
        }
        
        String filename = _context.getProperty("stat.logFile");
        if (filename == null)
            filename = "stats.log";
        if ( (_outFile != null) && (_outFile.equals(filename)) ) {
            // noop
        } else {
            if (_out != null) try { _out.close(); } catch (IOException ioe) {}
            _outFile = filename;
            try {
                _out = new BufferedWriter(new FileWriter(_outFile));
            } catch (IOException ioe) { ioe.printStackTrace(); }
        }
    }
    
    private class StatLogWriter implements Runnable {
        private SimpleDateFormat _fmt = new SimpleDateFormat("yyyyMMdd HH:mm:ss.SSS");
        public void run() {
            int writeStart = -1;
            int writeEnd = -1;
            while (true) {
                synchronized (_events) {
                    if (_eventNext > _lastWrite) {
                        if (_eventNext - _lastWrite < _flushFrequency)
                            try { _events.wait(30*1000); } catch (InterruptedException ie) {}
                    } else {
                        if (_events.length - 1 - _lastWrite + _eventNext < _flushFrequency)
                            try { _events.wait(30*1000); } catch (InterruptedException ie) {}
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
            }
        }
        
        private void writeEvents(int start, int end) {
            try {    
                updateFilters();
                int cur = start;
                while (cur != end) {
                    if (shouldLog(_events[cur].getStat())) {
                        String when = null;
                        synchronized (_fmt) {
                            when = _fmt.format(new Date(_events[cur].getTime()));
                        }
                        _out.write(when);
                        _out.write(" ");
                        if (_events[cur].getScope() == null)
                            _out.write("noScope ");
                        else
                            _out.write(_events[cur].getScope() + " ");
                        _out.write(_events[cur].getStat()+" ");
                        _out.write(_events[cur].getValue()+" ");
                        _out.write(_events[cur].getDuration()+"\n");
                    }
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
