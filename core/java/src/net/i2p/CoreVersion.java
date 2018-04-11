package net.i2p;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Expose a version string
 *
 */
public class CoreVersion {

    /** deprecated */
    public final static String ID = "Monotone";

    public final static String VERSION = "0.9.34";

    /**
     *  For Vuze.
     *  @return VERSION
     *  @since 0.9.19
     */
    public static String getVersion() {
        return VERSION;
    }

    public static void main(String args[]) {
        System.out.println("I2P Core version: " + VERSION);
        System.out.println("ID: " + ID);
    }
}
