/*
 * This is free software, do as you please.
 */

package net.i2p.util;

import java.io.File;

/**
 * gaaah:
 *   1) the CWD is /
 *   2) we can only access /data/data/net.i2p.router/files/
 *   3) you can't change your CWD in Java
 * so we have this lovely and the one in FileStreamFactory.
 *
 * @author zzz
 */
public class I2PFile extends File {

    public I2PFile (String f) {
        super("/data/data/net.i2p.router/files/" + f);
    }

    /** one level deep only */
    public I2PFile (File p, String f) {
        super("/data/data/net.i2p.router/files/" + p.getName(), f);
    }

}
