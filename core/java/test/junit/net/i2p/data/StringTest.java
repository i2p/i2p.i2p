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

import org.junit.Test;

/**
 * Test harness for the date structure
 *
 * @author jrandom
 */
public class StringTest {
    @Test
    public void testString() throws Exception{
        byte[] temp = null;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        DataHelper.writeString(baos, "Hello, I2P");
        temp = baos.toByteArray();


        String s = null;
        ByteArrayInputStream bais = new ByteArrayInputStream(temp);

        s = DataHelper.readString(bais);

        assertEquals(s, "Hello, I2P");
    }

}
