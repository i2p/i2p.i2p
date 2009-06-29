/*
 * public domain
 */

package net.i2p.util;

import java.io.File;

/**
 * This is pulled out and replaced in the android build.
 *
 * @author zzz
 */
public class I2PFile extends File {

    public I2PFile (String f) {
        super(f);
    }

    public I2PFile (File p, String f) {
        super(p, f);
    }

}
