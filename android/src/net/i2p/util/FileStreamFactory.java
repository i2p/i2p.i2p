/*
 * This is free software, do as you please.
 */

package net.i2p.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

import net.i2p.router.I2PAndroid;

/**
 * Use android static file stream methods
 * gaaah:
 *   1) the CWD is /
 *   2) we can only access /data/data/net.i2p.router/files/
 *   3) you can't change your CWD in Java
 * so we have this lovely and the one in I2PFile.
 *
 * @author zzz
 */
public class FileStreamFactory {
    private static final String DIR = "/data/data/net.i2p.router/files/";

    /** hopefully no path separators in string */
    public static FileInputStream getFileInputStream(String f) throws FileNotFoundException {
        System.err.println("Input file-s: " + I2PAndroid.getContext().getFileStreamPath(f).getAbsolutePath());
        return I2PAndroid.getContext().openFileInput(f);
    }

    public static FileInputStream getFileInputStream(File f) throws FileNotFoundException {
        System.err.println("Input file-f: " + getPath(f) +
                            ' ' + f.getAbsolutePath());
        //return I2PAndroid.getContext().openFileInput(f.getName());
        return new FileInputStream(getPath(f));
    }

    /** hopefully no path separators in string */
    public static FileOutputStream getFileOutputStream(String f) throws FileNotFoundException {
        System.err.println("Output file-s: " + I2PAndroid.getContext().getFileStreamPath(f).getAbsolutePath());
        return I2PAndroid.getContext().openFileOutput(f, 0);
    }

    public static FileOutputStream getFileOutputStream(File f) throws FileNotFoundException {
        System.err.println("Output file-f: " + getPath(f) +
                            ' ' + f.getAbsolutePath());
        //return I2PAndroid.getContext().openFileOutput(f.getName(), 0);
        return new FileOutputStream(getPath(f));
    }

    /**
     *  preserve path but convert /foo/blah to /data/data/net.i2p.router/files/foo/blah
     *  Although if the File arg was created with new I2PFile() then this isn't required
     *
     */
    private static String getPath(File f) {
        String abs = f.getAbsolutePath();
        if (abs.startsWith(DIR))
            return abs;
        return DIR + abs.substring(1);  // strip extra '/'
    }
}
