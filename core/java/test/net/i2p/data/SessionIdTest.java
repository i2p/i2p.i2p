package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.i2cp.SessionId;

/**
 * Test harness for loading / storing SessionId objects
 *
 * @author jrandom
 */
class SessionIdTest extends StructureTest {
    static {
        TestData.registerTest(new SessionIdTest(), "SessionId");
    }
    public DataStructure createDataStructure() throws DataFormatException {
        SessionId id = new SessionId();
        id.setSessionId(7);
        return id;
    }
    public DataStructure createStructureToRead() { return new SessionId(); }
}
