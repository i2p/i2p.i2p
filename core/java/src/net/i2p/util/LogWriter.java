package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Queue;

/**
 * Log writer thread that pulls log records from the LogManager and writes them to
 * the log.  This also periodically instructs the LogManager to reread its config
 * file.
 *
 * @since 0.9.19 pulled from FileLogWriter so Android may extend; renamed from LogWriterBase in 0.9.26
 */
abstract class LogWriter implements Runnable {
    /** every 10 seconds? why? Just have the gui force a reread after a change?? */
    private final static long CONFIG_READ_INTERVAL = 50 * 1000;
    final static long FLUSH_INTERVAL = 29 * 1000;
    private final static long MIN_FLUSH_INTERVAL = 2*1000;
    private final static long MAX_FLUSH_INTERVAL = 5*60*1000;
    private long _lastReadConfig;
    protected final LogManager _manager;

    protected volatile boolean _write;
    private LogRecord _last;
    private long _firstTimestamp;
    // ms
    private volatile long _flushInterval = FLUSH_INTERVAL;

    public LogWriter(LogManager manager) {
        _manager = manager;
        _lastReadConfig = Clock.getInstance().now();
    }

    /**
     *  File may not exist or have old logs in it if not opened yet
     *  @return non-null
     */
    public abstract String currentFile();

    /**
     * Write the provided LogRecord to the writer.
     * @param rec the LogRecord to write.
     * @param formatted a String pre-formatted from rec, may be ignored.
     */
    protected abstract void writeRecord(LogRecord rec, String formatted);
    /**
     * Write a single String verbatim to the writer.
     * @param priority the level to log the line at.
     * @param line the String to write.
     */
    protected abstract void writeRecord(int priority, String line);
    protected abstract void flushWriter();
    protected abstract void closeWriter();

    public void stopWriting() {
        _write = false;
    }

    /**
     *  @param interval ms
     *  @since 0.9.18
     */
    public void setFlushInterval(long interval) {
        _flushInterval = Math.min(MAX_FLUSH_INTERVAL, Math.max(MIN_FLUSH_INTERVAL, interval));
    }

    public void run() {
        _write = true;
        // don't bother on Android
        final boolean shouldReadConfig = !SystemVersion.isAndroid();
        try {
            while (_write) {
                flushRecords();
                if (_write && shouldReadConfig)
                    rereadConfig();
            }
        } catch (RuntimeException e) {
            System.err.println("Error writing the log: " + e);
            e.printStackTrace();
        }
        closeWriter();
    }

    public void flushRecords() { flushRecords(true); }

    public void flushRecords(boolean shouldWait) {
        try {
            // zero copy, drain the manager queue directly
            Queue<LogRecord> records = _manager.getQueue();
            if (records == null) return;
            if (!records.isEmpty()) {
                if (_last != null && _firstTimestamp < _manager.getContext().clock().now() - 30*60*1000)
                    _last = null;
                LogRecord rec;
                int dupCount = 0;
                while ((rec = records.poll()) != null) {
                    if (_manager.shouldDropDuplicates() && rec.equals(_last)) {
                        dupCount++;
                    } else {
                        if (dupCount > 0) {
                            writeDupMessage(dupCount, _last);
                            dupCount = 0;
                        }
                        writeRecord(rec);
                        _firstTimestamp = rec.getDate();
                    }
                    _last = rec;
                }
                if (dupCount > 0) {
                    writeDupMessage(dupCount, _last);
                }
                flushWriter();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (shouldWait) {
                try { 
                    synchronized (this) {
                        this.wait(_flushInterval); 
                    }
                } catch (InterruptedException ie) { // nop
                }
            }
        }
    }

    /**
     *  Write a msg with the date stamp of the last duplicate
     *  @since 0.9.21
     */
    private void writeDupMessage(int dupCount, LogRecord lastRecord) {
        String dmsg = dupMessage(dupCount, lastRecord, false);
        writeRecord(lastRecord.getPriority(), dmsg);
        if (_manager.getDisplayOnScreenLevel() <= lastRecord.getPriority() && _manager.displayOnScreen())
            System.out.print(dmsg);
        dmsg = dupMessage(dupCount, lastRecord, true);
        _manager.getBuffer().add(dmsg);
        if (lastRecord.getPriority() >= Log.CRIT)
            _manager.getBuffer().addCritical(dmsg);
    }

    /**
     *  Return a msg with the date stamp of the last duplicate
     *  @since 0.9.3
     */
    private String dupMessage(int dupCount, LogRecord lastRecord, boolean reverse) {
        String arrows = reverse ? (SystemVersion.isAndroid() ? "vvv" : "&darr;&darr;&darr;") : "^^^";
        return LogRecordFormatter.getWhen(_manager, lastRecord) + ' ' + arrows + ' ' +
               _t(dupCount, "1 similar message omitted", "{0} similar messages omitted") + ' ' + arrows +
               LogRecordFormatter.NL;
    }
    
    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /**
     *  gettext
     *  @since 0.9.3
     */
    private String _t(int a, String b, String c) {
        return Translate.getString(a, b, c, _manager.getContext(), BUNDLE_NAME);
    }

    private void rereadConfig() {
        long now = Clock.getInstance().now();
        if (now - _lastReadConfig > CONFIG_READ_INTERVAL) {
            _manager.rereadConfig();
            _lastReadConfig = now;
        }
    }

    private void writeRecord(LogRecord rec) {
        String val = LogRecordFormatter.formatRecord(_manager, rec, true);
        writeRecord(rec, val);

        // we always add to the console buffer, but only sometimes write to stdout
        _manager.getBuffer().add(val);
        if (rec.getPriority() >= Log.CRIT)
            _manager.getBuffer().addCritical(val);
        if (_manager.getDisplayOnScreenLevel() <= rec.getPriority()) {
            if (_manager.displayOnScreen()) {
                // wrapper and android logs already do time stamps, so reformat without the date
                if (_manager.getContext().hasWrapper() || SystemVersion.isAndroid())
                    System.out.print(LogRecordFormatter.formatRecord(_manager, rec, false));
                else
                    System.out.print(val);
            }
        }
    }
}
