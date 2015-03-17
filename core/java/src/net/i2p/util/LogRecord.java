package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Keep track of a log entry, unformatted.
 *
 */
class LogRecord {
    private final long _date;
    private final Class _source;
    private final String _name;
    private final String _threadName;
    private final int _priority;
    private final String _message;
    private final Throwable _throwable;

    public LogRecord(Class src, String name, String threadName, int priority, String msg, Throwable t) {
        _date = Clock.getInstance().now();
        _source = src;
        _name = name;
        _threadName = threadName;
        _priority = priority;
        _message = msg;
        _throwable = t;
    }

    public long getDate() {
        return _date;
    }

    public Class getSource() {
        return _source;
    }

    public String getSourceName() {
        return _name;
    }

    public String getThreadName() {
        return _threadName;
    }

    public int getPriority() {
        return _priority;
    }

    public String getMessage() {
        return _message;
    }

    public Throwable getThrowable() {
        return _throwable;
    }
}
