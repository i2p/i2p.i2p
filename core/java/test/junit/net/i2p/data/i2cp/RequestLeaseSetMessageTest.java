package net.i2p.data.i2cp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;

import net.i2p.data.StructureTest;
import net.i2p.data.DataStructure;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.TunnelIdTest;

/**
 * Test harness for loading / storing RequestLeaseSetMessage objects
 *
 * @author jrandom
 */
public class RequestLeaseSetMessageTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        RequestLeaseSetMessage msg = new RequestLeaseSetMessage();
        msg.setSessionId((SessionId)(new SessionIdTest()).createDataStructure());
        msg.setEndDate(new Date(1000*60*60*12));
        byte h[] = new byte[Hash.HASH_LENGTH];
        msg.addEndpoint(new Hash(h), (TunnelId)(new TunnelIdTest()).createDataStructure());
        return msg; 
    }
    public DataStructure createStructureToRead() { return new RequestLeaseSetMessage(); }
}
