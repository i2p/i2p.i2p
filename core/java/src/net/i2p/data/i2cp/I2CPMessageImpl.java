package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;

/**
 * Defines the base message implementation.
 *
 * @author jrandom
 */
public abstract class I2CPMessageImpl extends DataStructureImpl implements I2CPMessage {

    public I2CPMessageImpl() { // nop
    }

    /**
     * Validate the type and size of the message, and then read the message into the data structures.  <p />
     *
     * @throws IOException 
     */
    public void readMessage(InputStream in) throws I2CPMessageException, IOException {
        int length = 0;
        try {
            length = (int) DataHelper.readLong(in, 4);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error reading the length bytes", dfe);
        }
        if (length < 0) throw new I2CPMessageException("Invalid message length specified");
        int type = -1;
        try {
            type = (int) DataHelper.readLong(in, 1);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error reading the type byte", dfe);
        }
        readMessage(in, length, type);
    }

    /**
     * Read the body into the data structures
     *
     * @param length number of bytes in the message payload
     * @throws IOException
     */
    public void readMessage(InputStream in, int length, int type) throws I2CPMessageException, IOException {
        if (type != getType())
            throw new I2CPMessageException("Invalid message type (found: " + type + " supported: " + getType()
                                           + " class: " + getClass().getName() + ")");
        if (length < 0) throw new IOException("Negative payload size");

        /*
        byte buf[] = new byte[length];
        int read = DataHelper.read(in, buf);
        if (read != length)
            throw new IOException("Not able to read enough bytes [" + read + "] read, expected [ " + length + "]");

        ByteArrayInputStream bis = new ByteArrayInputStream(buf);

        doReadMessage(bis, length);
         */
        doReadMessage(in, length);
    }

    /**
     * Read in the payload part of the message (after the initial 4 byte size and 1
     * byte type)
     *
     * @param buf InputStream
     * @param size payload size
     * @throws I2CPMessageException
     * @throws IOException
     */
    protected abstract void doReadMessage(InputStream buf, int size) throws I2CPMessageException, IOException;

    /**
     * Write out the payload part of the message (not including the 4 byte size and
     * 1 byte type)
     *
     * @return byte array
     * @throws I2CPMessageException
     * @throws IOException
     */
    protected abstract byte[] doWriteMessage() throws I2CPMessageException, IOException;

    /**
     * Write out the full message to the stream, including the 4 byte size and 1 
     * byte type header.
     *
     * @throws IOException 
     */
    public void writeMessage(OutputStream out) throws I2CPMessageException, IOException {
        byte[] data = doWriteMessage();
        try {
            DataHelper.writeLong(out, 4, data.length);
            DataHelper.writeLong(out, 1, getType());
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to write the message length or type", dfe);
        }
        out.write(data);
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        try {
            readMessage(in);
        } catch (I2CPMessageException ime) {
            throw new DataFormatException("Error reading the message", ime);
        }
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        try {
            writeMessage(out);
        } catch (I2CPMessageException ime) {
            throw new DataFormatException("Error writing the message", ime);
        }
    }
}
