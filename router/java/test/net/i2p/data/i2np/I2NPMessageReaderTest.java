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

import net.i2p.data.DataFormatException;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageReader;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

/**
 * Test harness for loading / storing I2NP DatabaseStore message objects
 *
 * @author jrandom
 */
class I2NPMessageReaderTest implements I2NPMessageReader.I2NPMessageEventListener {
    private final static Log _log = new Log(I2NPMessageReaderTest.class);
    private static RouterContext _context = new RouterContext(null);
    
    public static void main(String args[]) {
        I2NPMessageReaderTest test = new I2NPMessageReaderTest();
        test.runTest();
        try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
    }
    
    public void runTest() {
        InputStream data = getData();
        test(data);
    }
    
    private InputStream getData() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DatabaseStoreMessage msg = (DatabaseStoreMessage)new DatabaseStoreMessageTest().createDataStructure();
            msg.writeBytes(baos);
            msg.writeBytes(baos);
            msg.writeBytes(baos);
            _log.debug("DB Store message in tunnel contains: " + msg);
            msg.writeBytes(baos);
        } catch (DataFormatException dfe) {
            _log.error("Error building data", dfe);
        } catch (IOException ioe) {
            _log.error("Error writing stream", ioe);
        }
        return new ByteArrayInputStream(baos.toByteArray());
    }
    
    private void test(InputStream in) {
        _log.debug("Testing the input stream");
        I2NPMessageReader reader = new I2NPMessageReader(_context, in, this);
        _log.debug("Created, beginning reading");
        reader.startReading();
        _log.debug("Reading commenced");
    }
    
    public void disconnected(I2NPMessageReader reader) {
        _log.debug("Disconnected");
    }
    
    public void messageReceived(I2NPMessageReader reader, I2NPMessage message, long msToRead) {
        _log.debug("Message received: " + message);
    }
    
    public void readError(I2NPMessageReader reader, Exception error) {
        _log.debug("Read error: " + error.getMessage(), error);
    }
    
}
