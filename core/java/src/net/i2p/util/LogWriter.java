package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Queue;

/**
 * Log writer thread that pulls log records from the LogManager, writes them to
 * the current logfile, and rotates the logs as necessary.  This also periodically
 * instructs the LogManager to reread its config file.
 *
 */
class LogWriter implements Runnable {
    /** every 10 seconds? why? Just have the gui force a reread after a change?? */
    private final static long CONFIG_READ_INTERVAL = 50 * 1000;
    private final static long FLUSH_INTERVAL = 9 * 1000;
    private long _lastReadConfig = 0;
    private long _numBytesInCurrentFile = 0;
    // volatile as it changes on log file rotation
    private volatile Writer _currentOut;
    private int _rotationNum = -1;
    private String _logFilenamePattern;
    private File _currentFile;
    private final LogManager _manager;

    private boolean _write;
    private static final int MAX_DISKFULL_MESSAGES = 8;
    private int _diskFullMessageCount;
    
    public LogWriter(LogManager manager) {
        _manager = manager;
        _lastReadConfig = Clock.getInstance().now();
    }

    public void stopWriting() {
        _write = false;
    }
    
    public void run() {
        _write = true;
        try {
            rotateFile();
            while (_write) {
                flushRecords();
                rereadConfig();
            }
            //System.err.println("Done writing");
        } catch (Exception e) {
            System.err.println("Error writing the log: " + e);
            e.printStackTrace();
        }
        closeFile();
    }

    public void flushRecords() { flushRecords(true); }
    public void flushRecords(boolean shouldWait) {
        try {
            // zero copy, drain the manager queue directly
            Queue<LogRecord> records = _manager.getQueue();
            if (records == null) return;
            if (!records.isEmpty()) {
                LogRecord rec;
                while ((rec = records.poll()) != null) {
                    writeRecord(rec);
                }
                try {
                    _currentOut.flush();
                } catch (IOException ioe) {
                    if (++_diskFullMessageCount < MAX_DISKFULL_MESSAGES)
                        System.err.println("Error writing the router log - disk full? " + ioe);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (shouldWait) {
                try { 
                    synchronized (this) {
                        this.wait(FLUSH_INTERVAL); 
                    }
                } catch (InterruptedException ie) { // nop
                }
            }
        }
    }
    
    public String currentFile() {
        return _currentFile != null ? _currentFile.getAbsolutePath() : "uninitialized";
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
        writeRecord(val);

        // we always add to the console buffer, but only sometimes write to stdout
        _manager.getBuffer().add(val);
        if (rec.getPriority() >= Log.CRIT)
            _manager.getBuffer().addCritical(val);
        if (_manager.getDisplayOnScreenLevel() <= rec.getPriority()) {
            if (_manager.displayOnScreen()) {
                // wrapper log already does time stamps, so reformat without the date
                if (System.getProperty("wrapper.version") != null)
                    System.out.print(LogRecordFormatter.formatRecord(_manager, rec, false));
                else
                    System.out.print(val);
            }
        }
    }

    private void writeRecord(String val) {
        if (val == null) return;
        if (_currentOut == null) {
            rotateFile();
            if (_currentOut == null)
                return; // hosed
        }

        try {
            _currentOut.write(val);
            // may be a little off if a lot of multi-byte chars, but unlikely
            _numBytesInCurrentFile += val.length();
        } catch (Throwable t) {
            if (!_write)
                return;
            if (++_diskFullMessageCount < MAX_DISKFULL_MESSAGES)
                System.err.println("Error writing log, disk full? " + t);
            //t.printStackTrace();
        }
        if (_numBytesInCurrentFile >= _manager.getFileSize()) {
            rotateFile();
        }
    }

    /**
     * Rotate to the next file (or the first file if this is the first call)
     *
     */
    private void rotateFile() {
        String pattern = _manager.getBaseLogfilename();
        File f = getNextFile(pattern);
        _currentFile = f;
        _numBytesInCurrentFile = 0;
        File parent = f.getParentFile();
        if (parent != null) {
            if (!parent.exists()) {
                File sd = new SecureDirectory(parent.getAbsolutePath());
                boolean ok = sd.mkdirs();
                if (!ok) {
                    System.err.println("Unable to create the parent directory: " + parent.getAbsolutePath());
                    //System.exit(0);
                }
            }
            if (!parent.isDirectory()) {
                System.err.println("Cannot put the logs in a subdirectory of a plain file: " + f.getAbsolutePath());
                //System.exit(0);
            }
        }
        closeFile();
        try {
            _currentOut = new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(f), "UTF-8"));
        } catch (IOException ioe) {
            System.err.println("Error rotating into [" + f.getAbsolutePath() + "]" + ioe);
        }
    }

    private void closeFile() {
        Writer out = _currentOut;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ioe) {}
        }
    }

    /**
     * Get the next file in the rotation
     *
     */
    private File getNextFile(String pattern) {
        File f = new File(pattern);
        File base = null;
        if (!f.isAbsolute())
            base = _manager.getContext().getLogDir();

        if ( (pattern.indexOf('#') < 0) && (pattern.indexOf('@') <= 0) ) {
            if (base != null)
                return new File(base, pattern);
            else
                return f;
        }
        
        int max = _manager.getRotationLimit();
        if (_rotationNum == -1) {
            return getFirstFile(base, pattern, max);
        }
             
        // we're in rotation, just go to the next  
        _rotationNum++;
        if (_rotationNum > max) _rotationNum = 0;

        String newf = replace(pattern, _rotationNum);
        if (base != null)
            return new File(base, newf);
        return new File(newf);
    }

    /**
     * Retrieve the first file, updating the rotation number accordingly
     *
     */
    private File getFirstFile(File base, String pattern, int max) {
        for (int i = 0; i < max; i++) {
            File f;
            if (base != null)
                f = new File(base, replace(pattern, i));
            else
                f = new File(replace(pattern, i));
            if (!f.exists()) {
                _rotationNum = i;
                return f;
            }
        }

        // all exist, pick the oldest to replace
        File oldest = null;
        for (int i = 0; i < max; i++) {
            File f;
            if (base != null)
                f = new File(base, replace(pattern, i));
            else
                f = new File(replace(pattern, i));
            if (oldest == null) {
                oldest = f;
            } else {
                if (f.lastModified() < oldest.lastModified()) {
                    _rotationNum = i;
                    oldest = f;
                }
            }
        }
        return oldest;
    }

    private static final String replace(String pattern, int num) {
        char c[] = pattern.toCharArray();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < c.length; i++) {
            if ( (c[i] != '#') && (c[i] != '@') )
                buf.append(c[i]);
            else
                buf.append(num);
        }
        return buf.toString();
    }
}
