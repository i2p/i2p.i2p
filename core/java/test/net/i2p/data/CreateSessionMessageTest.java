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
import net.i2p.data.i2cp.CreateSessionMessage;
import net.i2p.data.i2cp.SessionConfig;

/**
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
class CreateSessionMessageTest extends StructureTest {
    static {
        TestData.registerTest(new CreateSessionMessageTest(), "CreateSessionMessage");
    }
    public DataStructure createDataStructure() throws DataFormatException {
        CreateSessionMessage msg = new CreateSessionMessage();
        msg.setSessionConfig((SessionConfig)(new SessionConfigTest()).createDataStructure());
        return msg; 
    }
    public DataStructure createStructureToRead() { return new CreateSessionMessage(); }
}
