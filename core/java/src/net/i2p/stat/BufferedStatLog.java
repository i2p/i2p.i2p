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

/**
 * 
 */
public class BufferedStatLog implements StatLog {
    private I2PAppContext _context;
    private List _events;
    /** flush stat events to disk after this many events (or 30s)*/
    private int _flushFrequency;
    private List _statFilters;
    private BufferedWriter _out;
    private String _outFile;
    
    public BufferedStatLog(I2PAppContext ctx) {
        _context = ctx;
        _events = new ArrayList(1000);
        _statFilters = new ArrayList(10);
        _flushFrequency = 1000;
        I2PThread writer = new I2PThread(new StatLogWriter(), "StatLogWriter");
        writer.setDaemon(true);
        writer.start();
    }
    
    public void addData(String scope, String stat, long value, long duration) {
        synchronized (_events) {
            _events.add(new StatEvent(scope, stat, value, duration));
            if (_events.size() > _flushFrequency)
                _events.notifyAll();
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
            StringTokenizer tok = new StringTokenizer(val, ",");
            synchronized (_statFilters) {
                _statFilters.clear();
                while (tok.hasMoreTokens())
                    _statFilters.add(tok.nextToken().trim());
            }
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
        private SimpleDateFormat _fmt = new SimpleDateFormat("yyyyMMdd hh:mm:ss.SSS");
        public void run() {
            List cur = new ArrayList(1000);
            while (true) {
                synchronized (_events) {
                    if (_events.size() < _flushFrequency) {
                        try { _events.wait(30*1000); } catch (InterruptedException ie) {}
                    } 
                    cur.addAll(_events);
                    _events.clear();
                }
                if (cur.size() > 0) {
                    writeEvents(cur);
                    cur.clear();
                }
            }
        }
        
        private void writeEvents(List events) {
            try {    
                updateFilters();
                for (int i = 0; i < events.size(); i++) {
                    StatEvent evt = (StatEvent)events.get(i);
                    if (!shouldLog(evt.getStat())) continue;
                    String when = null;
                    synchronized (_fmt) {
                        when = _fmt.format(new Date(evt.getTime()));
                    }
                    _out.write(when);
                    _out.write(" ");
                    if (evt.getScope() == null)
                        _out.write("noScope ");
                    else
                        _out.write(evt.getScope() + " ");
                    _out.write(evt.getStat()+" ");
                    _out.write(evt.getValue()+" ");
                    _out.write(evt.getDuration()+"\n");
                }
                _out.flush();
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }
    }
    
    private class StatEvent {
        private long _time;
        private String _scope;
        private String _stat;
        private long _value;
        private long _duration;
        
        public StatEvent(String scope, String stat, long value, long duration) {
            _scope = scope;
            _stat = stat;
            _value = value;
            _duration = duration;
            _time = _context.clock().now();
        }
        
        public long getTime() { return _time; } 
        public String getScope() { return _scope; }
        public String getStat() { return _stat; }
        public long getValue() { return _value; }
        public long getDuration() { return _duration; }
    }    
}
