package net.i2p.data;
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

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.SessionId;
import net.i2p.util.Log;

/**
 * Test harness for loading / storing SendMessageMessage objects
 *
 * @author jrandom
 */
class MessagePayloadMessageTest extends StructureTest {
    private final static Log _log = new Log(MessagePayloadMessage.class);

    static {
        TestData.registerTest(new MessagePayloadMessageTest(), "MessagePayloadMessage");
    }
    public DataStructure createDataStructure() throws DataFormatException {
        MessagePayloadMessage msg = new MessagePayloadMessage();
        msg.setMessageId((MessageId)(new MessageIdTest()).createDataStructure());
        msg.setPayload((Payload)(new PayloadTest()).createDataStructure());
        msg.setSessionId((SessionId)(new SessionIdTest()).createDataStructure());
        return msg; 
    }
    public DataStructure createStructureToRead() { return new MessagePayloadMessage(); }   
    public String testData(InputStream inputStream) {
        try {
            DataStructure structure = createStructureToRead();
            structure.readBytes(inputStream);
            ((MessagePayloadMessage)structure).getPayload().setUnencryptedData(((MessagePayloadMessage)structure).getPayload().getEncryptedData());
            return structure.toString();
        } catch (DataFormatException dfe) {
            _log.error("Error reading the data structure", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error reading the data structure", ioe);
            return null;
        }
    }
 
}
