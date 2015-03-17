package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import junit.framework.TestCase;

/**
 * Test harness for the date structure
 *
 * @author jrandom
 */
public class UnsignedIntegerTest extends TestCase{
    
    public void testLong() throws Exception{
        byte[] temp = null;
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        DataHelper.writeLong(baos, 4, 42);
        temp = baos.toByteArray();
        
        
        long l;
        ByteArrayInputStream bais = new ByteArrayInputStream(temp);
        
        l = DataHelper.readLong(bais, 4);
        
        assertEquals(42, l);
    }
}