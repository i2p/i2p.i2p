package net.i2p;
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

/**
 * @author Comwiz
 */
public class AllCoreTests {
    
    public static Test suite() {
        TestSuite suite = new TestSuite("net.i2p.AllCoreTests");
        
        suite.addTest(net.i2p.client.I2PClientTestSuite.suite());
        suite.addTest(net.i2p.crypto.CryptoTestSuite.suite());
        suite.addTest(net.i2p.data.DataTestSuite.suite());
        suite.addTest(net.i2p.stat.StatTestSuite.suite());
        suite.addTest(net.i2p.util.UtilTestSuite.suite());
        
        return suite;
    }
}