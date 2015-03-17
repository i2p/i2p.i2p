package net.i2p.util;
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
import java.util.zip.GZIPInputStream;

import junit.framework.TestCase;
import net.i2p.data.DataHelper;


public class ResettableGZIPOutputStreamTest extends TestCase {
    public void testResettableGZIPOutputStream() throws Exception{
        byte b[] = "hi, how are you today?".getBytes();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ResettableGZIPOutputStream o = new ResettableGZIPOutputStream(baos);
        o.write(b);
        o.finish();
        o.flush();
        byte compressed[] = baos.toByteArray();
        
        /*ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        GZIPOutputStream gzo = new GZIPOutputStream(baos2);
        gzo.write(b);
        gzo.finish();
        gzo.flush();
        byte compressed2[] = baos2.toByteArray();
        
        assertTrue(DataHelper.eq(compressed, compressed2));*/
        
        GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed));
        byte rv[] = new byte[128];
        int read = in.read(rv);
        assertTrue(DataHelper.eq(rv, 0, b, 0, b.length));
        
    }
}