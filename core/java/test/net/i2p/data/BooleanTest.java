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

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Test harness for the boolean structure
 *
 * @author jrandom
 */
class BooleanTest implements TestDataGenerator, TestDataPrinter {
    static {
        TestData.registerGenerator(new BooleanTest(), "Boolean");
        TestData.registerPrinter(new BooleanTest(), "Boolean");
    }
    private static final Log _log = new Log(BooleanTest.class);
    
    public byte[] getData() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataHelper.writeBoolean(baos, Boolean.TRUE);
            return baos.toByteArray();
        } catch (DataFormatException dfe) {
            _log.error("Error writing the boolean", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error writing the boolean", ioe);
            return null;
        }
    }
    
    public String testData(InputStream inputStream) {
        try {
            Boolean b = DataHelper.readBoolean(inputStream);
            return ""+b;
        } catch (DataFormatException dfe) {
            _log.error("Error reading the boolean", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error reading the boolean", ioe);
            return null;
        }
    }
    
    
}
