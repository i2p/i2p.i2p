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
    public final static String ID = "Git";

    /**
     *  The version used when checking for router updates,
     *  and exchanged between router and client over I2CP.
     *  This is the marketing and user-visible version.
     *
     *  If we ever need a point release for a specific
     *  architecture only, append ".1" to VERSION
     *  and leave PUBLISHED_VERSION unchanged.
     *  Otherwise, the same as PUBLISHED_VERSION.
     *  RouterVersion.FULL_VERSION is suggested for display to the user.
     */
    public final static String VERSION = "2.7.0";

    /**
     *  The version published in the netdb via StatisticsManager.
     *  This is the API version.
     *  It must not go to 1.x for several years, because through
     *  0.9.49, the Sybil analyzer blocked releases that didn't
     *  start with "0.9."
     *
     *  If we ever need a point release for a specific
     *  architecture only, append ".1" to VERSION
     *  and leave PUBLISHED_VERSION unchanged.
     *  Otherwise, the same as VERSION.
     *  RouterVersion.FULL_VERSION is suggested for display to the user.
     *
     *  @since 0.9.46
     */
    public final static String PUBLISHED_VERSION = "0.9.64";

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
        System.out.println("I2P API version:  " + PUBLISHED_VERSION);
        System.out.println("ID: " + ID);
    }
}
