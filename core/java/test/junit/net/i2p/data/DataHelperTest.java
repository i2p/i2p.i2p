package net.i2p.data;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;
import java.util.TimeZone;

import org.junit.Test;

/**
 * basic unit tests for the DataHelper
 *
 */
public class DataHelperTest {

    /**
     * Test to/from/read/writeLong with every 1, 2, and 4 byte value, as
     * well as some 8 byte values.
     */
    @Test
    public void testLong() throws Exception{
        for (int i = 0; i <= 0xFF; i+=4)
            checkLong(1, i);
        for (long i = 0; i <= 0xFFFF; i+=16)
            checkLong(2, i);
        for (long i = 0; i <= 0xFFFFFF; i +=128)
            checkLong(3, i);
        for (long i = 0; i <= 0xFFFFFFFFl; i+=512)
            checkLong(4, i);
        // i know, doesnt test (2^63)-(2^64-1)
        for (long i = Long.MAX_VALUE - 0xFFFFFFl; i < Long.MAX_VALUE; i++)
            checkLong(8, i);
    }
    private static void checkLong(int numBytes, long value) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(numBytes);
        DataHelper.writeLong(baos, numBytes, value);
        byte written[] = baos.toByteArray();
        byte extract[] = DataHelper.toLong(numBytes, value);
        assertTrue(extract.length == numBytes);
        assertTrue(DataHelper.eq(written, extract));
        byte extract2[] = new byte[numBytes];
        DataHelper.toLong(extract2, 0, numBytes, value);
        assertTrue(DataHelper.eq(extract, extract2));

        long read = DataHelper.fromLong(extract, 0, numBytes);
        assertTrue(read == value);

        ByteArrayInputStream bais = new ByteArrayInputStream(written);
        read = DataHelper.readLong(bais, numBytes);
        assertTrue(read == value);
        read = DataHelper.fromLong(written, 0, numBytes);
        assertTrue(read == value);

    }

    @Test
    public void testDate() throws Exception{
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.set(Calendar.YEAR, 1970);
        cal.set(Calendar.MONTH, 0);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        checkDate(cal.getTime());

        cal.set(Calendar.SECOND, 1);
        checkDate(cal.getTime());

        cal.set(Calendar.YEAR, 1999);
        cal.set(Calendar.MONTH, 11);
        cal.set(Calendar.DAY_OF_MONTH, 31);
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        checkDate(cal.getTime());

        cal.set(Calendar.YEAR, 2000);
        cal.set(Calendar.MONTH, 0);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        checkDate(cal.getTime());

        cal.setTimeInMillis(System.currentTimeMillis());
        checkDate(cal.getTime());

        cal.set(Calendar.SECOND, cal.get(Calendar.SECOND)+10);
        checkDate(cal.getTime());

        cal.set(Calendar.YEAR, 1969);
        cal.set(Calendar.MONTH, 11);
        cal.set(Calendar.DAY_OF_MONTH, 31);
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        boolean error = false;
        try{
            checkDate(cal.getTime());
        }catch(Exception e){
            error = true;
        }
        assertTrue(error);
    }

    @SuppressWarnings("deprecation")
    private void checkDate(Date when) throws Exception{
        byte buf[] = new byte[DataHelper.DATE_LENGTH];
        DataHelper.toDate(buf, 0, when.getTime());
        byte tbuf[] = DataHelper.toDate(when);
        assertTrue(DataHelper.eq(tbuf, buf));
        Date time = DataHelper.fromDate(buf, 0);
        assertEquals(when.getTime(), time.getTime());
    }

    @Test
    public void testCompress() throws Exception{
        Random r = new Random();
        for (int size = 0; size < 32*1024; size+=32){   // Original had size++, changed value because
                                                        // speed was a problem. -Comwiz
            byte data[] = new byte[size];
            r.nextBytes(data);
            byte compressed[] = DataHelper.compress(data);
            byte decompressed[] = DataHelper.decompress(compressed);
            assertTrue(DataHelper.eq(data, decompressed));
        }
    }

    @Test
    public void testSkip() throws Exception {
        final int sz = 256;
        TestInputStream tis = new TestInputStream(sz);
        DataHelper.skip(tis, sz);
        try {
            DataHelper.skip(tis, 1);
            fail();
        } catch (IOException ioe) {}

        DataHelper.skip(tis, 0);

        try {
            DataHelper.skip(tis, -1);
            fail("skipped negative?");
        } catch (IllegalArgumentException expected) {}
    }

    private static class TestInputStream extends ByteArrayInputStream {
        private final Random r = new Random();

        public TestInputStream(int size) {
            super(new byte[size]);
            r.nextBytes(buf);
        }

        /** skip a little at a time, or sometimes zero */
        @Override
        public long skip(long n) {
            return super.skip(Math.min(n, r.nextInt(4)));
        }
    }
}
