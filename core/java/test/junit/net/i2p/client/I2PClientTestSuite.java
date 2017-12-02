package net.i2p.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */
 
import junit.framework.Test;
import junit.framework.TestSuite;
import net.i2p.client.datagram.DatagramTest;

/**
 * @author Comwiz
 */
public class I2PClientTestSuite {
    
    public static Test suite() {
        TestSuite suite = new TestSuite("net.i2p.client.I2PClientTestSuite");
        
        suite.addTestSuite(I2PClientTest.class);
        suite.addTestSuite(DatagramTest.class);
        
        return suite;
    }
}
