package net.i2p.crypto;
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
 * @author comwiz
 */
public class CryptoTestSuite {
    
    public static Test suite() {
        
        TestSuite suite = new TestSuite("net.i2p.crypto.CryptoTestSuite");
        
        suite.addTestSuite(AES256Test.class);
        suite.addTestSuite(CryptixAESEngineTest.class);
        suite.addTestSuite(CryptixRijndael_AlgorithmTest.class);
        suite.addTestSuite(DSATest.class);
        suite.addTestSuite(ElGamalTest.class);
        suite.addTestSuite(HMACSHA256Test.class);
        suite.addTestSuite(KeyGeneratorTest.class);
        suite.addTestSuite(SHA1HashTest.class);
        suite.addTestSuite(SHA256Test.class);
        suite.addTestSuite(SipHashInlineTest.class);
        
        return suite;
    }
}
