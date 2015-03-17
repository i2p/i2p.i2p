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

import junit.framework.TestCase;

public class LookAheadInputStreamTest extends TestCase {
    public void testLookAheadInputStream() throws Exception{
        byte buf[] = new byte[32];
        for (int i = 0; i < 32; i++)
            buf[i] = (byte)i;
        ByteArrayInputStream bais = new ByteArrayInputStream(buf);
        
        LookaheadInputStream lis = new LookaheadInputStream(8);
        lis.initialize(bais);
        byte rbuf[] = new byte[32];
        int read = lis.read(rbuf);
        assertEquals(read,24);
        for (int i = 0; i < 24; i++)
            assertEquals(rbuf[i],(byte)i);
        for (int i = 0; i < 8; i++)
            assertEquals(lis.getFooter()[i],(byte)(i+24));
        
        
        for (int size = 9; size < 32*1024; size+=100) {
            buf = new byte[size];
            new java.util.Random().nextBytes(buf);
            bais = new ByteArrayInputStream(buf);
            
            lis = new LookaheadInputStream(8);
            lis.initialize(bais);
            rbuf = new byte[size];
            read = lis.read(rbuf);
            assertEquals(read,(size-8));
            for (int i = 0; i < (size-8); i++)
                assertEquals(rbuf[i],buf[i]);
            for (int i = 0; i < 8; i++)
                assertEquals(lis.getFooter()[i],buf[i+(size-8)]);
        }
    }
}