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


public class ReusableGZIPOutputStreamTest extends TestCase {
    public void testReusableGZIPOutputStream() throws Exception{
        {
            byte b[] = "hi, how are you today?".getBytes();
            ReusableGZIPOutputStream o = ReusableGZIPOutputStream.acquire();
            o.write(b);
            o.finish();
            o.flush();
            byte compressed[] = o.getData();
            ReusableGZIPOutputStream.release(o);
            
            GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed));
            byte rv[] = new byte[128];
            int read = in.read(rv);
            assertTrue(DataHelper.eq(rv, 0, b, 0, b.length));
        }
        
        for (int size = 500; size < 64*1024; size+=100) {
            byte b[] = new byte[size];
            new java.util.Random().nextBytes(b);
            
            ReusableGZIPOutputStream o = ReusableGZIPOutputStream.acquire();
            o.write(b);
            o.finish();
            o.flush();
            byte compressed[] = o.getData();
            ReusableGZIPOutputStream.release(o);
            
            GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed));
            ByteArrayOutputStream baos2 = new ByteArrayOutputStream(256*1024);
            byte rbuf[] = new byte[128];
            while (true) {
                int read = in.read(rbuf);
                if (read == -1)
                    break;
                baos2.write(rbuf, 0, read);
            }
            byte rv[] = baos2.toByteArray();
            assertTrue(DataHelper.eq(rv, 0, b, 0, b.length));
        }
    }
}