package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Test harness for the mapping structure (in java, a Properties map)
 *
 * @author jrandom
 */
class MappingTest implements TestDataGenerator, TestDataPrinter {
    static {
        TestData.registerGenerator(new MappingTest(), "Mapping");
        TestData.registerPrinter(new MappingTest(), "Mapping");
    }
    private static final Log _log = new Log(MappingTest.class);
    
    public byte[] getData() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Properties options = new Properties();
        options.setProperty("key1", "val1");
        options.setProperty("key2", "val2");
        options.setProperty("key3", "val3");
        try {
            DataHelper.writeProperties(baos, options);
            return baos.toByteArray();
        } catch (DataFormatException dfe) {
            _log.error("Error writing the mapping", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error writing the mapping", ioe);
            return null;
        }
    }
    
    public String testData(InputStream inputStream) {
        try {
            Properties options = DataHelper.readProperties(inputStream);
            return DataHelper.toString(options);
        } catch (DataFormatException dfe) {
            _log.error("Error reading the mapping", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error reading the mapping", ioe);
            return null;
        }
    }
    
    
}
