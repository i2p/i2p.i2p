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
import java.util.Date;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Test harness for the boolean structure
 *
 * @author jrandom
 */
class DateTest implements TestDataGenerator, TestDataPrinter {
    static {
        TestData.registerGenerator(new DateTest(), "Date");
        TestData.registerPrinter(new DateTest(), "Date");
    }
    private static final Log _log = new Log(DateTest.class);
    
    public byte[] getData() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataHelper.writeDate(baos, new Date());
            return baos.toByteArray();
        } catch (DataFormatException dfe) {
            _log.error("Error writing the date", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error writing the date", ioe);
            return null;
        }
    }
    
    public String testData(InputStream inputStream) {
        try {
            Date d = DataHelper.readDate(inputStream);
            return ""+d;
        } catch (DataFormatException dfe) {
            _log.error("Error reading the date", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error reading the date", ioe);
            return null;
        }
    }
    
    
}
