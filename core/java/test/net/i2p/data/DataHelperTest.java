package net.i2p.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * basic unit tests for the DataHelper
 *
 */
public class DataHelperTest {
    private I2PAppContext _context;
    private Log _log;
    
    public DataHelperTest(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(DataHelperTest.class);
    }
    
    public void runTests() {
        // long (read/write/to/from)
        testLong();
        // date (read/write/to/from)
        testDate();
        // string 
        // properties
        // boolean
        // readline
        // compress
    }
    
    /**
     * Test to/from/read/writeLong with every 1, 2, and 4 byte value, as
     * well as some 8 byte values.
     */
    public void testLong() {
        for (int i = 0; i <= 0xFF; i++)
            testLong(1, i);
        System.out.println("Test 1byte passed");
        for (long i = 0; i <= 0xFFFF; i++)
            testLong(2, i);
        System.out.println("Test 2byte passed");
        for (long i = 0; i <= 0xFFFFFF; i ++)
            testLong(3, i);
        System.out.println("Test 3byte passed");
        for (long i = 0; i <= 0xFFFFFFFF; i++)
            testLong(4, i);
        System.out.println("Test 4byte passed");
        // i know, doesnt test (2^63)-(2^64-1)
        for (long i = Long.MAX_VALUE - 0xFFFFFFl; i < Long.MAX_VALUE; i++)
            testLong(8, i);
        System.out.println("Test 8byte passed");
    }
    private static void testLong(int numBytes, long value) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(numBytes);
            DataHelper.writeLong(baos, numBytes, value);
            byte written[] = baos.toByteArray();
            byte extract[] = DataHelper.toLong(numBytes, value);
            if (extract.length != numBytes)
                throw new RuntimeException("testLong("+numBytes+","+value+") FAILED (len="+extract.length+")");
            if (!DataHelper.eq(written, extract))
                throw new RuntimeException("testLong("+numBytes+","+value+") FAILED");
            byte extract2[] = new byte[numBytes];
            DataHelper.toLong(extract2, 0, numBytes, value);
            if (!DataHelper.eq(extract, extract2))
                throw new RuntimeException("testLong("+numBytes+","+value+") FAILED on toLong");
            
            long read = DataHelper.fromLong(extract, 0, numBytes);
            if (read != value)
                throw new RuntimeException("testLong("+numBytes+","+value+") FAILED on read (" + read + ")");
            
            ByteArrayInputStream bais = new ByteArrayInputStream(written);
            read = DataHelper.readLong(bais, numBytes);
            if (read != value)
                throw new RuntimeException("testLong("+numBytes+","+value+") FAILED on readLong (" + read + ")");
            read = DataHelper.fromLong(written, 0, numBytes);
            if (read != value)
                throw new RuntimeException("testLong("+numBytes+","+value+") FAILED on fromLong (" + read + ")");
        } catch (Exception e) {
            throw new RuntimeException("test(" + numBytes +","+ value +"): " + e.getMessage());
        }
    }

    private void testDate() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.set(Calendar.YEAR, 1970);
        cal.set(Calendar.MONTH, 0);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        testDate(cal.getTime());
        
        cal.set(Calendar.SECOND, 1);
        testDate(cal.getTime());
        
        cal.set(Calendar.YEAR, 1999);
        cal.set(Calendar.MONTH, 11);
        cal.set(Calendar.DAY_OF_MONTH, 31);
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        testDate(cal.getTime());
        
        cal.set(Calendar.YEAR, 2000);
        cal.set(Calendar.MONTH, 0);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        testDate(cal.getTime());
        
        cal.setTimeInMillis(System.currentTimeMillis());
        testDate(cal.getTime());
        
        cal.set(Calendar.SECOND, cal.get(Calendar.SECOND)+10);
        testDate(cal.getTime());

        try {
            cal.set(Calendar.YEAR, 1969);
            cal.set(Calendar.MONTH, 11);
            cal.set(Calendar.DAY_OF_MONTH, 31);
            cal.set(Calendar.HOUR_OF_DAY, 23);
            cal.set(Calendar.MINUTE, 59);
            cal.set(Calendar.SECOND, 59);
            testDate(cal.getTime());
            System.err.println("foo!  this should fail");
        } catch (RuntimeException re) {
            // should fail on dates prior to the epoch
        }
    }
    
    private void testDate(Date when) {
        try {
            byte buf[] = new byte[DataHelper.DATE_LENGTH];
            DataHelper.toDate(buf, 0, when.getTime());
            byte tbuf[] = DataHelper.toDate(when);
            if (!DataHelper.eq(tbuf, buf))
                throw new RuntimeException("testDate("+when.toString()+") failed on toDate");
            Date time = DataHelper.fromDate(buf, 0);
            if (when.getTime() != time.getTime())
                throw new RuntimeException("testDate("+when.toString()+") failed (" + time.toString() + ")");
            System.out.println("eq: " + time);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }
    
    public static void main(String args[]) {
        DataHelperTest test = new DataHelperTest(I2PAppContext.getGlobalContext());
        test.runTests();
    }
}
