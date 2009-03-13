/*
 * public domain
 */

package net.i2p.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;


/**
 * This is pulled out and replaced in the android build.
 *
 * @author zzz
 */
public class FileStreamFactory {

    public static FileInputStream getFileInputStream(String f) throws FileNotFoundException {
        return new FileInputStream(f);
    }

    public static FileInputStream getFileInputStream(File f) throws FileNotFoundException {
        return new FileInputStream(f);
    }

    public static FileOutputStream getFileOutputStream(String f) throws FileNotFoundException {
        return new FileOutputStream(f);
    }

    public static FileOutputStream getFileOutputStream(File f) throws FileNotFoundException {
        return new FileOutputStream(f);
    }

}
