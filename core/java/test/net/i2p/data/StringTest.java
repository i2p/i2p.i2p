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
class StringTest implements TestDataGenerator, TestDataPrinter {
    static {
        TestData.registerGenerator(new StringTest(), "String");
        TestData.registerPrinter(new StringTest(), "String");
    }
    private static final Log _log = new Log(StringTest.class);
    
    public byte[] getData() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataHelper.writeString(baos, "Hello, I2P");
            return baos.toByteArray();
        } catch (DataFormatException dfe) {
            _log.error("Error writing the string", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error writing the string", ioe);
            return null;
        }
    }
    
    public String testData(InputStream inputStream) {
        try {
            String str = DataHelper.readString(inputStream);
            return ""+str;
        } catch (DataFormatException dfe) {
            _log.error("Error reading the string", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error reading the string", ioe);
            return null;
        }
    }
    
    
}
