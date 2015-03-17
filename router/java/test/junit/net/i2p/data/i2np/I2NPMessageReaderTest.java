package net.i2p.data.i2np;
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

import junit.framework.TestCase;
import net.i2p.data.DataFormatException;
import net.i2p.router.RouterContext;

/**
 * Test harness for loading / storing I2NP DatabaseStore message objects
 *
 * @author jrandom
 */
public class I2NPMessageReaderTest extends TestCase implements I2NPMessageReader.I2NPMessageEventListener{
    
    public void setUp(){}
    
    public void testI2NPMessageReader() throws IOException, DataFormatException{
        InputStream data = getData();
        test(data);
    }
    
    private InputStream getData() throws IOException, DataFormatException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DatabaseStoreMessage msg = (DatabaseStoreMessage)new DatabaseStoreMessageTest().createDataStructure();
        msg.writeBytes(baos);
        msg.writeBytes(baos);
        msg.writeBytes(baos);
        msg.writeBytes(baos);
        return new ByteArrayInputStream(baos.toByteArray());
    }
    
    private void test(InputStream in) {
        I2NPMessageReader reader = new I2NPMessageReader(new RouterContext(null), in, this);
        reader.startReading();
    }
    
    public void disconnected(I2NPMessageReader reader) {
    }
    
    public void messageReceived(I2NPMessageReader reader, I2NPMessage message, long msToRead, int size) {
    }
    
    public void readError(I2NPMessageReader reader, Exception error) {
    }
    
}
