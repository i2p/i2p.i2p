package net.i2p.data.i2cp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataFormatException;
import net.i2p.data.Payload;
import net.i2p.data.PayloadTest;

/**
 * Test harness for loading / storing SendMessageMessage objects
 *
 * @author jrandom
 */
 
 public class MessagePayloadMessageTest extends I2CPTstBase {
    public I2CPMessageImpl createDataStructure() throws DataFormatException {
        MessagePayloadMessage msg = new MessagePayloadMessage();
        msg.setMessageId(123);
        msg.setPayload((Payload)(new PayloadTest()).createDataStructure());
        msg.setSessionId(321);
        return msg; 
    }
    public I2CPMessageImpl createStructureToRead() { return new MessagePayloadMessage(); }
    
}
