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
import net.i2p.data.Destination;
import net.i2p.data.DestinationTest;
import net.i2p.data.Payload;
import net.i2p.data.PayloadTest;

/**
 * Test harness for loading / storing SendMessageMessage objects
 *
 * @author jrandom
 */
 
 public class SendMessageMessageTest extends I2CPTstBase {
    
    public I2CPMessageImpl createDataStructure() throws DataFormatException {
        SendMessageMessage msg = new SendMessageMessage();
        msg.setDestination((Destination)(new DestinationTest()).createDataStructure());
        msg.setPayload((Payload)(new PayloadTest()).createDataStructure());
        msg.setSessionId((SessionId)(new SessionIdTest()).createDataStructure());
        msg.setNonce(1);
        return msg; 
    }
    public I2CPMessageImpl createStructureToRead() { return new SendMessageMessage(); }  
    
}
