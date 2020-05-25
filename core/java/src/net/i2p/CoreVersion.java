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
 * Expose a version string.
 *
 * See also: RouterVersion, VersionComparator, and the update subsystem.
 *
 */
public class CoreVersion {

    /** deprecated */
    public final static String ID = "Monotone";

    /**
     *  The version used when checking for router updates,
     *  and exchanged between router and client over I2CP.
     *  If we ever need a point release for a specific
     *  architecture only, append ".1" to VERSION
     *  and leave PUBLISHED_VERSION unchanged.
     *  Otherwise, the same as PUBLISHED_VERSION.
     *  RouterVersion.FULL_VERSION is suggested for display to the user.
     */
    public final static String VERSION = "0.9.46";

    /**
     *  The version published in the netdb via StatisticsManager.
     *  If we ever need a point release for a specific
     *  architecture only, append ".1" to VERSION
     *  and leave PUBLISHED_VERSION unchanged.
     *  Otherwise, the same as VERSION.
     *  RouterVersion.FULL_VERSION is suggested for display to the user.
     *
     *  @since 0.9.46
     */
    public final static String PUBLISHED_VERSION = VERSION;

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
