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
import net.i2p.data.Destination;
import net.i2p.data.LeaseSet;
import net.i2p.data.PublicKey;
import net.i2p.data.Signature;
import net.i2p.data.SigningPublicKey;

/**
 * Test harness for loading / storing Lease objects
 *
 * @author jrandom
 */
class LeaseSetTest extends StructureTest {
    static {
        TestData.registerTest(new LeaseSetTest(), "LeaseSet");
    }
    public DataStructure createDataStructure() throws DataFormatException {
        LeaseSet leaseSet = new LeaseSet();
        leaseSet.setDestination((Destination)(new DestinationTest()).createDataStructure());
        leaseSet.setEncryptionKey((PublicKey)(new PublicKeyTest()).createDataStructure());
	leaseSet.setSignature((Signature)(new SignatureTest()).createDataStructure());
	leaseSet.setSigningKey((SigningPublicKey)(new SigningPublicKeyTest()).createDataStructure());
	//leaseSet.setVersion(42l);
        return leaseSet; 
    }
    public DataStructure createStructureToRead() { return new LeaseSet(); }
}
