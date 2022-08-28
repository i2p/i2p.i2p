package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.zip.GZIPOutputStream;

import net.i2p.data.DataHelper;

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
        if (_numBytesInCurrentFile >= _manager.getFileSize() - 1024) {
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
        closeWriter(_currentFile, false);
    }

    /**
     *  Gzip the closed file
     *
     *  @param threadGzipper if true, spin off a thread
     *  @since 0.9.55
     */
    private void closeWriter(File currentFile, boolean threadGzipper) {
        Writer out = _currentOut;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ioe) {}
        }
        if (_manager.shouldGzip() && currentFile != null && currentFile.length() >= _manager.getMinGzipSize()) {
            Thread gzipper = new Gzipper(currentFile);
            if (threadGzipper) {
                gzipper.setPriority(Thread.MIN_PRIORITY);
                gzipper.start();  // rotate
            } else {
                gzipper.run();  // shutdown
            }
        }
    }

    /**
     * Rotate to the next file (or the first file if this is the first call)
     *
     * Caller must synch
     */
    private void rotateFile() {
        File old = _currentFile;
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
        closeWriter(old, true);
        if (_manager.shouldGzip())
            (new File(f.getPath() + ".gz")).delete();
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
            // check for file or file.gz
            if (!f.exists() && !(_manager.shouldGzip() && (new File(f.getPath() + ".gz").exists()))) {
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
                // set file or file.gz for last mod check
                File ff, oo;
                if (!_manager.shouldGzip() || f.exists())
                    ff = f;
                else
                    ff = new File(f.getPath() + ".gz");
                if (!_manager.shouldGzip() || oldest.exists())
                    oo = oldest;
                else
                    oo = new File(oldest.getPath() + ".gz");

                if (ff.lastModified() < oo.lastModified()) {
                    _rotationNum = i;
                    oldest = f;
                }
            }
        }
        return oldest;
    }

    private static final String replace(String pattern, int num) {
        int len = pattern.length();
        StringBuilder buf = new StringBuilder(len + 1);
        for (int i = 0; i < len; i++) {
            char c = pattern.charAt(i);
            if ( (c != '#') && (c != '@') )
                buf.append(c);
            else
                buf.append(num);
        }
        return buf.toString();
    }

    /**
     * @since 0.9.55
     */
    private static class Gzipper extends I2PAppThread {
        private final File _f;

        public Gzipper(File f) {
            super("Log file compressor");
            _f = f;
        }

        public void run() {
            File to = new File(_f.getPath() + ".gz");
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new BufferedInputStream(new FileInputStream(_f));
                out = new BufferedOutputStream(new GZIPOutputStream(new SecureFileOutputStream(to)));
                DataHelper.copy(in, out);
            } catch (IOException ioe) {
                System.out.println("Error compressing log file " + _f);
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
                if (out != null) try { out.close(); } catch (IOException ioe) {}
                to.setLastModified(_f.lastModified());
                _f.delete();
            }
        }
    }
}
