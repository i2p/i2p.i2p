package net.i2p.i2pcontrol;

import java.util.HashSet;
import java.util.Set;

public class I2PControlVersion {
    /** The current version of I2PControl */
    public final static String VERSION = "0.12.0";

    /** The current version of the I2PControl API being primarily being implemented */
    public final static int API_VERSION = 1;

    /** The supported versions of the I2PControl API */
    public final static Set<Integer> SUPPORTED_API_VERSIONS;

    static {
        SUPPORTED_API_VERSIONS = new HashSet<Integer>();
        SUPPORTED_API_VERSIONS.add(1);
    }
}
