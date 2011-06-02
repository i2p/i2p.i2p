package net.i2p.util;

import java.io.File;

/**
 *  setXXX() not available until API level 9 (Platform Version 2.3)
 *  @since 0.8.7
 */
public class SecureFile extends SecureDirectory {

    public SecureFile(String pathname) {
        super(pathname);
    }

    public SecureFile(String parent, String child) {
        super(parent, child);
    }

    public SecureFile(File parent, String child) {
        super(parent, child);
    }
}
