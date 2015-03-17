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
import java.io.IOException;
import java.io.InputStream;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA256Generator;
import net.i2p.util.Log;

/**
 * Base implementation of all data structures
 *
 * @author jrandom
 */
public abstract class DataStructureImpl implements DataStructure {
    
    public String toBase64() {
        byte data[] = toByteArray();
        if (data == null)
            return null;

        return Base64.encode(data);
    }
    public void fromBase64(String data) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        byte bytes[] = Base64.decode(data);
        fromByteArray(bytes);
    }
    public Hash calculateHash() {
        byte data[] = toByteArray();
        if (data != null) return SHA256Generator.getInstance().calculateHash(data);
        return null;
    }
    public byte[] toByteArray() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            writeBytes(baos);
            return baos.toByteArray();
        } catch (IOException ioe) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
            log.error("Error writing out the byte array", ioe);
            return null;
        } catch (DataFormatException dfe) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
            log.error("Error writing out the byte array", dfe);
            return null;
        }
    }
    public void fromByteArray(byte data[]) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            readBytes(bais);
        } catch (IOException ioe) {
            throw new DataFormatException("Error reading the byte array", ioe);
        }
    }

    /**
     * Repeated reads until the buffer is full or IOException is thrown
     *
     * @return number of bytes read (should always equal target.length)
     */
    protected int read(InputStream in, byte target[]) throws IOException {
        return DataHelper.read(in, target);
    }
}
