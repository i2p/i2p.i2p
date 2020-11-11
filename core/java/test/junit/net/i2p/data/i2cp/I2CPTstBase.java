package net.i2p.data.i2cp;
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

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;

/**
 * Utility class for wrapping I2CP message tests
 *
 * @since 0.9.48
 */

public abstract class I2CPTstBase {

    /** create a populated structure for writing */
    public abstract I2CPMessageImpl createDataStructure() throws DataFormatException;

    /** create an unpopulated structure for reading */
    public abstract I2CPMessageImpl createStructureToRead();

    @Test
    public void testStructure() throws Exception{
        byte[] temp = null;

        I2CPMessageImpl orig;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        orig = createDataStructure();
        orig.writeBytes(baos);


        temp = baos.toByteArray();

        I2CPMessageImpl ds;
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
