package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterInfoTest;
import net.i2p.data.StructureTest;
import net.i2p.util.Clock;

/**
 * Test harness for loading / storing I2NP DatabaseStore message objects
 *
 * @author jrandom
 */
public class DatabaseStoreMessageTest extends StructureTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();

    public DataStructure createDataStructure() throws DataFormatException {
        DatabaseStoreMessage msg = new DatabaseStoreMessage(I2PAppContext.getGlobalContext());
        RouterInfo info = (RouterInfo)new RouterInfoTest().createDataStructure();
        msg.setMessageExpiration(Clock.getInstance().now());
        msg.setUniqueId(666);
        msg.setEntry(info);
        return msg;
    }
    
    public DataStructure createStructureToRead() { 
    	return new DatabaseStoreMessage(I2PAppContext.getGlobalContext()); 
    }
    
    @Override
    @Test
    public void testStructure() throws Exception {
        exception.expect(UnsupportedOperationException.class);
        super.testStructure();
    }
}
