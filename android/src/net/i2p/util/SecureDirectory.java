package net.i2p.util;

import java.io.File;

/**
 *  setXXX() not available until API level 9 (Platform Version 2.3)
 *  @since 0.8.7
 */
public class SecureDirectory extends File {

    public SecureDirectory(String pathname) {
        super(pathname);
    }

    public SecureDirectory(String parent, String child) {
        super(parent, child);
    }

    public SecureDirectory(File parent, String child) {
        super(parent, child);
    }
}
