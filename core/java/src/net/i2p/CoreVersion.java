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
    public final static String ID = "$Revision: 1.30 $ $Date: 2005/03/06 19:07:28 $";
    public final static String VERSION = "0.5.0.3";

    public static void main(String args[]) {
        System.out.println("I2P Core version: " + VERSION);
        System.out.println("ID: " + ID);
    }
}