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
 * Test harness for the boolean structure
 *
 * @author jrandom
 */
public class BooleanTest {
    @SuppressWarnings("deprecation")
    @Test
    public void testBoolean() throws Exception{
        byte[] temp = null;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        DataHelper.writeBoolean(baos, Boolean.TRUE);
        temp = baos.toByteArray();


        Boolean b = null;
        ByteArrayInputStream bais = new ByteArrayInputStream(temp);

        b = DataHelper.readBoolean(bais);

        assertEquals(Boolean.TRUE, b);
    }

}
