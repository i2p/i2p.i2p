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
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * File-based log writer thread that pulls log records from the LogManager,
 * writes them to the current logfile, and rotates the logs as necessary.
 *
 * @since 0.9.26 moved from LogWriter
 */
class FileLogWriter extends LogWriter {
    // volatile as it changes on log file rotation
    private volatile Writer _currentOut;
    private int _rotationNum = -1;
    private File _currentFile;
    private long _numBytesInCurrentFile;

    private static final int MAX_DISKFULL_MESSAGES = 8;
    private int _diskFullMessageCount;

    public FileLogWriter(LogManager manager) {
        super(manager);
    }

    /**
     *  File may not exist or have old logs in it if not opened yet
     *  @return non-null
     */
    public synchronized String currentFile() {
        if (_currentFile != null)
            return _currentFile.getAbsolutePath();
        String rv = getNextFile().getAbsolutePath();
        // so it doesn't increment every time we call this
        _rotationNum = -1;
        return rv;
    }

    protected void writeRecord(LogRecord rec, String formatted) {
    	writeRecord(rec.getPriority(), formatted);
    }

    protected synchronized void writeRecord(int priority, String val) {
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
     *  @since 0.9.19
     */
    protected void flushWriter() {
        try {
            if (_currentOut != null)
                _currentOut.flush();
        } catch (IOException ioe) {
            if (_write && ++_diskFullMessageCount < MAX_DISKFULL_MESSAGES)
                System.err.println("Error writing the router log - disk full? " + ioe);
        }
    }

    /**
     *  @since 0.9.19 renamed from closeFile()
     */
    protected void closeWriter() {
        Writer out = _currentOut;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ioe) {}
        }
    }

    /**
     * Rotate to the next file (or the first file if this is the first call)
     *
     * Caller must synch
     */
    private void rotateFile() {
        File f = getNextFile();
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
        closeWriter();
        try {
            _currentOut = new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(f), "UTF-8"));
        } catch (IOException ioe) {
            if (++_diskFullMessageCount < MAX_DISKFULL_MESSAGES)
                System.err.println("Error creating log file [" + f.getAbsolutePath() + "]" + ioe);
        }
    }

    /**
     * Get the next file in the rotation
     *
     * Caller must synch
     */
    private File getNextFile() {
        String pattern = _manager.getBaseLogfilename();
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
     * Caller must synch
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
