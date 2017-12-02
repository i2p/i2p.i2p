package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Date;

import org.junit.Test;

/**
 * Test harness for the date structure
 *
 * @author jrandom
 */
public class DateTest {
    @Test
    public void testDate() throws Exception{
        byte[] temp = null;

        Date orig = new Date();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        DataHelper.writeDate(baos, orig);
        temp = baos.toByteArray();

        Date d = null;
        ByteArrayInputStream bais = new ByteArrayInputStream(temp);

        d = DataHelper.readDate(bais);

        assertEquals(orig, d);
    }
}
