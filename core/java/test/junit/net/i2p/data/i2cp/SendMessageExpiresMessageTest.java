package net.i2p.data.i2cp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by str4d in 2012 and released into the public domain 
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
import net.i2p.data.DateAndFlags;
import net.i2p.data.DateAndFlagsTest;

/**
 * Test harness for loading / storing SendMessageExpiresMessage objects
 *
 * @author str4d
 */
 
 public class SendMessageExpiresMessageTest extends I2CPTstBase {
    
    public I2CPMessageImpl createDataStructure() throws DataFormatException {
        SendMessageExpiresMessage msg = new SendMessageExpiresMessage();
        msg.setDestination((Destination)(new DestinationTest()).createDataStructure());
        msg.setPayload((Payload)(new PayloadTest()).createDataStructure());
        msg.setSessionId((SessionId)(new SessionIdTest()).createDataStructure());
        msg.setNonce(1);
        DateAndFlags daf = (DateAndFlags)(new DateAndFlagsTest()).createDataStructure();
        msg.setExpiration(daf.getDate());
        msg.setFlags(daf.getFlags());
        return msg; 
    }
    public I2CPMessageImpl createStructureToRead() { return new SendMessageExpiresMessage(); }  
    
}
