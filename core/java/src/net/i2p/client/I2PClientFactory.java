package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Provide a means of hooking into an appropriate I2PClient implementation
 *
 * @author jrandom
 */
public class I2PClientFactory {
    /** Create a new instance of the appropriate I2PClient
     * @return client implementation
     */
    public static I2PClient createClient() {
        return new I2PClientImpl();
    }
}