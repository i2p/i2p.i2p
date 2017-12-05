package net.i2p.data;
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

/**
 * Test harness for loading / storing Lease objects
 *
 * @author jrandom
 */
public class LeaseSetTest extends StructureTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();

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

    @Test
    public void failsToGetLeaseWhenEmpty() {
        // create test subject
        LeaseSet subj = new LeaseSet();

        // should contain no leases now.
        exception.expect(IndexOutOfBoundsException.class);
        subj.getLease(0);
    }

    @Test
    public void failsToGetInvalidLease() {
        // create test subject
        LeaseSet subj = new LeaseSet();

        // this shouldn't work either
        exception.expect(IndexOutOfBoundsException.class);
        subj.getLease(-1);
    }

    @Test
    public void testAddLeaseNull() {
        // create test subject
        LeaseSet subj = new LeaseSet();

        // now add an null lease
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("erm, null lease");
        subj.addLease(null);
    }

    @Test
    public void testAddLeaseInvalid() {
        // create test subject
        LeaseSet subj = new LeaseSet();

        // try to add completely invalid lease(ie. no data)
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("erm, lease has no gateway");
        subj.addLease(new Lease());
    }
}
