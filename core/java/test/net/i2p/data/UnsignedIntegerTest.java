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
 * Test harness for the numerical structure (in java, an UnsignedInteger)
 *
 * @author jrandom
 */
class UnsignedIntegerTest implements TestDataGenerator, TestDataPrinter {
    static {
        TestData.registerGenerator(new UnsignedIntegerTest(), "UnsignedInteger");
        TestData.registerPrinter(new UnsignedIntegerTest(), "UnsignedInteger");
    }
    private static final Log _log = new Log(UnsignedIntegerTest.class);
    
    public byte[] getData() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataHelper.writeLong(baos, 4, 42);
            return baos.toByteArray();
        } catch (DataFormatException dfe) {
            _log.error("Error writing the integer", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error writing the integer", ioe);
            return null;
        }
    }
    
    public String testData(InputStream inputStream) {
        try {
            long val = DataHelper.readLong(inputStream, 4);
            return ""+val;
        } catch (DataFormatException dfe) {
            _log.error("Error reading the integer", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error reading the integer", ioe);
            return null;
        }
    }
    
    
}
