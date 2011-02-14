package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */


/**
 * Test harness for loading / storing Lease objects
 *
 * @author jrandom
 */
public class LeaseSetTest extends StructureTest {
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
    
    public void testGetLeaseInvalid() {
        // create test subject
        LeaseSet subj = new LeaseSet();
        
        // should contain no leases now..
        try {
            assertNull(subj.getLease(0));
        } catch(RuntimeException exc) {
            // all good
        }
        
        // this shouldn't work either
        try {
            assertNull(subj.getLease(-1));
        } catch(RuntimeException exc) {
            // all good
        }
    }
    
    public void testAddLeaseNull() {
        // create test subject
        LeaseSet subj = new LeaseSet();
        
        // now add an null lease
        try {
            subj.addLease(null);
            fail("Failed at failing.");
        } catch(IllegalArgumentException exc) {
            // all good
        }
    }
    
    public void testAddLeaseInvalid() {
        // create test subject
        LeaseSet subj = new LeaseSet();
        
        // try to add completely invalid lease(ie. no data)
        try {
            subj.addLease(new Lease());
            fail("Failed at failing.");
        } catch(IllegalArgumentException exc) {
            // all good
        }
    }
            
}
