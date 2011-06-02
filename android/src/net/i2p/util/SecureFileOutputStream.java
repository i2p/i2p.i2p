package net.i2p.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

import net.i2p.I2PAppContext;

/**
 *  setXXX() not available until API level 9 (Platform Version 2.3)
 *  @since 0.8.7
 */
public class SecureFileOutputStream extends FileOutputStream {

    /**
     *  super()
     */
    public SecureFileOutputStream(String file) throws FileNotFoundException {
        super(file);
    }

    /**
     *  super()
     */
    public SecureFileOutputStream(String file, boolean append) throws FileNotFoundException {
        super(file, append);
    }

    /**
     *  super()
     */
    public SecureFileOutputStream(File file) throws FileNotFoundException {
        super(file);
    }

    /**
     *  super()
     */
    public SecureFileOutputStream(File file, boolean append) throws FileNotFoundException {
        super(file, append);
    }

    /** @return false */
    static boolean canSetPerms() {
        return false;
    }

    /**
     *  noop
     */
    public static void setPerms(File f) {
    }
}
