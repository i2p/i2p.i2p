package net.i2p.util;

import java.io.File;

/**
 * Same as File but sets the file mode after mkdir() so it can
 * be read and written by the owner only (i.e. 700 on linux)
 * As of 0.8.2, just use SecureFile instead of this.
 *
 * @since 0.8.1
 * @author zzz
 */
public class SecureDirectory extends File {

    protected static final boolean isNotWindows = !System.getProperty("os.name").startsWith("Win");

    public SecureDirectory(String pathname) {
        super(pathname);
    }

    public SecureDirectory(String parent, String child) {
        super(parent, child);
    }

    public SecureDirectory(File parent, String child) {
        super(parent, child);
    }

    /**
     *  Sets directory to mode 700 if the directory is created
     */
    @Override
    public boolean mkdir() {
        boolean rv = super.mkdir();
        if (rv)
            setPerms();
        return rv;
    }

    /**
     *  Sets directory to mode 700 if the directory is created
     *  Does NOT change the mode of other created directories
     */
    @Override
    public boolean mkdirs() {
        boolean rv = super.mkdirs();
        if (rv)
            setPerms();
        return rv;
    }

    /**
     *  Tries to set the permissions to 700,
     *  ignores errors
     */
    protected void setPerms() {
        if (!SecureFileOutputStream.canSetPerms())
            return;
        try {
            setReadable(false, false);
            setReadable(true, true);
            setWritable(false, false);
            setWritable(true, true);
            if (isNotWindows) {
                setExecutable(false, false);
                setExecutable(true, true);
            }
        } catch (Throwable t) {
            // NoSuchMethodException or NoSuchMethodError if we somehow got the
            // version detection wrong or the JVM doesn't support it
        }
    }
}
