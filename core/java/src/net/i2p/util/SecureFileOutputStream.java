package net.i2p.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

/**
 * Same as FileOutputStream but sets the file mode so it can only
 * be read and written by the owner only (i.e. 600 on linux)
 *
 * @since 0.8.1
 * @author zzz
 */
public class SecureFileOutputStream extends FileOutputStream {

    private static final boolean canSetPerms =
        (new VersionComparator()).compare(System.getProperty("java.version"), "1.6") >= 0;

    /**
     *  Sets output file to mode 600
     */
    public SecureFileOutputStream(String file) throws FileNotFoundException {
        super(file);
        setPerms(new File(file));
    }

    /**
     *  Sets output file to mode 600 only if append = false
     *  (otherwise it is presumed to be 600 already)
     */
    public SecureFileOutputStream(String file, boolean append) throws FileNotFoundException {
        super(file, append);
        //if (!append)
            setPerms(new File(file));
    }

    /**
     *  Sets output file to mode 600
     */
    public SecureFileOutputStream(File file) throws FileNotFoundException {
        super(file);
        setPerms(file);
    }

    /**
     *  Sets output file to mode 600 only if append = false
     *  (otherwise it is presumed to be 600 already)
     */
    public SecureFileOutputStream(File file, boolean append) throws FileNotFoundException {
        super(file, append);
        //if (!append)
            setPerms(file);
    }

    /**
     *  Tries to set the permissions to 600,
     *  ignores errors
     */
    private static void setPerms(File f) {
        if (!canSetPerms)
            return;
        try {
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
        } catch (Throwable t) {
            // NoSuchMethodException or NoSuchMethodError if we somehow got the
            // version detection wrong or the JVM doesn't support it
        }
    }
}
