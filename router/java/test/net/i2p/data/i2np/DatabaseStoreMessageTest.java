package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.StructureTest;
import net.i2p.data.TestData;
import net.i2p.data.RouterInfo;
import net.i2p.data.RouterInfoTest;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.util.Clock;

/**
 * Test harness for loading / storing I2NP DatabaseStore message objects
 *
 * @author jrandom
 */
class DatabaseStoreMessageTest extends StructureTest {
    static {
        TestData.registerTest(new DatabaseStoreMessageTest(), "DatabaseStoreMessage");
    }
    public DataStructure createDataStructure() throws DataFormatException {
        DatabaseStoreMessage msg = new DatabaseStoreMessage(_context);
        RouterInfo info = (RouterInfo)new RouterInfoTest().createDataStructure();
        msg.setKey(info.getIdentity().getHash());
        msg.setMessageExpiration(Clock.getInstance().now());
        msg.setUniqueId(42);
        msg.setRouterInfo(info);
        return msg;
    }
    public DataStructure createStructureToRead() { return new DatabaseStoreMessage(_context); }
    
    public static void main(String args[]) { TestData.main(new String[] { "test", "i2np.DatabaseStoreMessage", "foo.dat" }); }
}
