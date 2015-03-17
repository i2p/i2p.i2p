package net.i2p.util;

import junit.framework.Test;
import junit.framework.TestSuite;

/**
 * Test suite for all available tests in the net.i2p.util package
 *
 * @author comwiz
 */
public class UtilTestSuite {
    
    public static Test suite() {
        TestSuite suite = new TestSuite("net.i2p.util.UtilTestSuite");
        
        suite.addTestSuite(LogSettingsTest.class);
        suite.addTestSuite(LookAheadInputStreamTest.class);
        suite.addTestSuite(ResettableGZIPInputStreamTest.class);
        suite.addTestSuite(ResettableGZIPOutputStreamTest.class);
        suite.addTestSuite(ReusableGZIPInputStreamTest.class);
        suite.addTestSuite(ReusableGZIPOutputStreamTest.class);
        
        return suite;
    }
}