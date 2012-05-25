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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import net.i2p.I2PAppContext;

/**
 * Log writer thread that pulls log records from the LogManager, writes them to
 * the current logfile, and rotates the logs as necessary.  This also periodically
 * instructs the LogManager to reread its config file.
 *
 */
class LogWriter implements Runnable {
    /** every 10 seconds? why? Just have the gui force a reread after a change?? */
    private final static long CONFIG_READ_ITERVAL = 10 * 1000;
    private long _lastReadConfig = 0;
    private long _numBytesInCurrentFile = 0;
    private OutputStream _currentOut; // = System.out
    private int _rotationNum = -1;
    private String _logFilenamePattern;
    private File _currentFile;
    private LogManager _manager;

    private boolean _write;
    
    private LogWriter() { // nop
    }

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
            System.err.println("Done writing");
        } catch (Exception e) {
            System.err.println("Error writing the logs: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void flushRecords() { flushRecords(true); }
    public void flushRecords(boolean shouldWait) {
        try {
            List records = _manager._removeAll();
            if (records == null) return;
            for (int i = 0; i < records.size(); i++) {
                LogRecord rec = (LogRecord) records.get(i);
                writeRecord(rec);
            }
            if (records.size() > 0) {
                try {
                    _currentOut.flush();
                } catch (IOException ioe) {
                    System.err.println("Error flushing the records");
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (shouldWait) {
                try { 
                    synchronized (this) {
                        this.wait(10*1000); 
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
        if (now - _lastReadConfig > CONFIG_READ_ITERVAL) {
            _manager.rereadConfig();
            _lastReadConfig = now;
        }
    }

    private void writeRecord(LogRecord rec) {
        String val = LogRecordFormatter.formatRecord(_manager, rec);
        writeRecord(val);

        // we always add to the console buffer, but only sometimes write to stdout
        _manager.getBuffer().add(val);
        if (rec.getPriority() >= Log.CRIT)
            _manager.getBuffer().addCritical(val);
        if (_manager.getDisplayOnScreenLevel() <= rec.getPriority()) {
            if (_manager.displayOnScreen()) {
                System.out.print(val);
            }
        }
    }

    private void writeRecord(String val) {
        if (val == null) return;
        if (_currentOut == null) rotateFile();

        byte b[] = new byte[val.length()];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte)val.charAt(i);
        try {
            _currentOut.write(b);
            _numBytesInCurrentFile += b.length;
        } catch (Throwable t) {
            System.err.println("Error writing record, disk full?");
            t.printStackTrace();
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
                boolean ok = parent.mkdirs();
                if (!ok) {
                    System.err.println("Unable to create the parent directy: " + parent.getAbsolutePath());
                    System.exit(0);
                }
            }
            if (!parent.isDirectory()) {
                System.err.println("wtf, we cannot put the logs in a subdirectory of a plain file!  we want to stre the log as " + f.getAbsolutePath());
                System.exit(0);
            }
        }
        try {
            _currentOut = new FileOutputStream(f);
        } catch (IOException ioe) {
            System.err.println("Error rotating into [" + f.getAbsolutePath() + "]");
            ioe.printStackTrace();
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
            base = I2PAppContext.getGlobalContext().getLogDir();

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

        return new File(replace(pattern, _rotationNum));
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
