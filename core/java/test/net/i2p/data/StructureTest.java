package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.util.Log;

/**
 * Utility class for wrapping data structure tests
 *
 * @author jrandom
 */
public abstract class StructureTest implements TestDataGenerator, TestDataPrinter {
    private static final Log _log = new Log(StructureTest.class);
    
    public abstract DataStructure createDataStructure() throws DataFormatException;
    public abstract DataStructure createStructureToRead();

    public byte[] getData() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataStructure structure = createDataStructure();
            structure.writeBytes(baos);
        } catch (DataFormatException dfe) {
            _log.error("Error writing the data structure", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error writing the data structure", ioe);
            return null;
        }
        return baos.toByteArray();
    }
    
    public String testData(InputStream inputStream) {
        try {
            DataStructure structure = createStructureToRead();
            structure.readBytes(inputStream);
	    return structure.toString() + "\n\nIn base 64: " + structure.toBase64();
        } catch (DataFormatException dfe) {
            _log.error("Error reading the data structure", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error reading the data structure", ioe);
            return null;
        }
    }
    
    
}
