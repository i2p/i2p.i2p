package net.i2p;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import org.junit.runners.Suite;
import org.junit.runner.RunWith;

/**
 * @author str4d
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    net.i2p.client.I2PClientTestSuite.class,
    net.i2p.crypto.CryptoTestSuite.class,
    net.i2p.data.DataTestSuite.class,
    net.i2p.stat.StatTestSuite.class,
    net.i2p.util.UtilTestSuite.class,
})
public class AllCoreTests {
}
