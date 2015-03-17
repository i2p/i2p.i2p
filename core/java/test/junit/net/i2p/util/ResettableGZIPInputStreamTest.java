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
import java.util.zip.GZIPOutputStream;

import junit.framework.TestCase;
import net.i2p.data.DataHelper;


public class ResettableGZIPInputStreamTest extends TestCase {
    public void testResettableGZIPInputStream() throws Exception{
        for (int size = 129; size < 64*1024; size+=100) {
            byte b[] = new byte[size];
            new java.util.Random().nextBytes(b);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream(size);
            GZIPOutputStream o = new GZIPOutputStream(baos);
            o.write(b);
            o.finish();
            o.flush();
            byte compressed[] = baos.toByteArray();
            
            ResettableGZIPInputStream in = new ResettableGZIPInputStream(new ByteArrayInputStream(compressed));
            ByteArrayOutputStream baos2 = new ByteArrayOutputStream(size);
            byte rbuf[] = new byte[512];
            while (true) {
                int read = in.read(rbuf);
                if (read == -1)
                    break;
                baos2.write(rbuf, 0, read);
            }
            byte rv[] = baos2.toByteArray();
            assertEquals(rv.length,b.length);
            
            assertTrue(DataHelper.eq(rv, 0, b, 0, b.length));
            
        }
        
        byte orig[] = "ho ho ho, merry christmas".getBytes();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        GZIPOutputStream o = new GZIPOutputStream(baos);
        o.write(orig);
        o.finish();
        o.flush();
        o.close();
        byte compressed[] = baos.toByteArray();
        
        ResettableGZIPInputStream i = new ResettableGZIPInputStream();
        i.initialize(new ByteArrayInputStream(compressed));
        byte readBuf[] = new byte[128];
        int read = i.read(readBuf);
        assertEquals(read,orig.length);
        for (int j = 0; j < read; j++)
            assertEquals(readBuf[j],orig[j]);
        assertEquals(-1,i.read());
        
    }
}