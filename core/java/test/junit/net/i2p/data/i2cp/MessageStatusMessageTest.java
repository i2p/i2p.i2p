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

/**
 * Test harness for loading / storing MessageStatusMessage objects
 *
 * @author jrandom
 */
public class MessageStatusMessageTest extends I2CPTstBase {
    public I2CPMessageImpl createDataStructure() throws DataFormatException {
        MessageStatusMessage msg = new MessageStatusMessage();
        msg.setSessionId(42);
        msg.setMessageId(41);
        msg.setSize(1024*1024*42L);
        msg.setStatus(MessageStatusMessage.STATUS_AVAILABLE);
        msg.setNonce(1);
        return msg; 
    }
    public I2CPMessageImpl createStructureToRead() { return new MessageStatusMessage(); }
}
