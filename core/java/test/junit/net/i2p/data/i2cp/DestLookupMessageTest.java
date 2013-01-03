package net.i2p.data.i2cp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by str4d in 2012 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.StructureTest;
import net.i2p.data.DataStructure;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.util.RandomSource;

/**
 * Test harness for loading / storing DestLookupMessage objects
 *
 * @author str4d
 */
public class DestLookupMessageTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        byte h[] = new byte[Hash.HASH_LENGTH];
        RandomSource.getInstance().nextBytes(h);
        Hash hash = new Hash(h);
        DestLookupMessage msg = new DestLookupMessage(hash);
        return msg;
    }
    public DataStructure createStructureToRead() { return new DestLookupMessage(); }
}
