package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.CoreVersion;

/**
 * Expose a version string
 *
 */
public class RouterVersion {
    public final static String ID = "$Revision: 1.348 $ $Date: 2006/02/18 01:38:30 $";
    public final static String VERSION = "0.6.1.10";
    public final static long BUILD = 4;
    public static void main(String args[]) {
        System.out.println("I2P Router version: " + VERSION + "-" + BUILD);
        System.out.println("Router ID: " + RouterVersion.ID);
        System.out.println("I2P Core version: " + CoreVersion.VERSION);
        System.out.println("Core ID: " + CoreVersion.ID);
    }
}
