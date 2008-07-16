package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.i2cp.SessionId;
import net.i2p.data.i2cp.SessionStatusMessage;

/**
 * Test harness for loading / storing SessionStatusMessage objects
 *
 * @author jrandom
 */
public class SessionStatusMessageTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        SessionStatusMessage msg = new SessionStatusMessage();
        msg.setSessionId((SessionId)(new SessionIdTest()).createDataStructure());
        msg.setStatus(SessionStatusMessage.STATUS_CREATED);
        return msg; 
    }
    public DataStructure createStructureToRead() { return new SessionStatusMessage(); }
}
