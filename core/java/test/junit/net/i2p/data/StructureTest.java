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
 * Utility class for wrapping data structure tests
 *
 * @author jrandom
 */

public abstract class StructureTest {

    /** create a populated structure for writing */
    public abstract DataStructure createDataStructure() throws DataFormatException;

    /** create an unpopulated structure for reading */
    public abstract DataStructure createStructureToRead();

    @Test
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


        // I2CP message classes don't implement equals()
        if (!getClass().getName().startsWith("net.i2p.data.i2cp."))
            assertEquals(orig, ds);

        // Not all classes implement equals, so write out again
        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        ds.writeBytes(baos2);
        byte[] temp2 = baos2.toByteArray();
        assert(DataHelper.eq(temp, temp2));
    }
}
