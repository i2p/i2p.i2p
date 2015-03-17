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
 * Utility class for wrapping data structure tests
 *
 * @author jrandom
 */

public abstract class StructureTest extends TestCase{
    
    public abstract DataStructure createDataStructure() throws DataFormatException;
    public abstract DataStructure createStructureToRead();
    
    public void testStructure() throws Exception{
        byte[] temp = null;
        
        DataStructure orig;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        orig = createDataStructure();
        orig.writeBytes(baos);
        
        
        temp = baos.toByteArray();
        
        DataStructure ds;
        ByteArrayInputStream bais = new ByteArrayInputStream(temp);
        
        ds = createStructureToRead();
        ds.readBytes(bais);
        
        assertEquals(orig, ds);
    }
    
}