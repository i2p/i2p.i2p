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

import java.io.ByteArrayOutputStream;
import java.util.Date;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/**
 * Test harness for loading / storing Lease objects
 *
 * @author jrandom
 */
public class LeaseTest extends StructureTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();

    public DataStructure createDataStructure() throws DataFormatException {
        Lease lease = new Lease();
        lease.setEndDate(new Date(1000*60*2));
        byte h[] = new byte[Hash.HASH_LENGTH];
        lease.setGateway(new Hash(h));
        StructureTest tst = new TunnelIdTest();
        lease.setTunnelId((TunnelId)tst.createDataStructure());

        return lease; 
    }
    public DataStructure createStructureToRead() { return new Lease(); }

    /* TODO: Delete this if Lease.getNumSuccess() / getNumFailure() get deleted
    public void testNumSuccessFail() throws Exception{
        Lease lease = new Lease();
        lease.setEndDate(new Date(1000*60*2));
        byte h[] = new byte[Hash.HASH_LENGTH];
        lease.setGateway(new Hash(h));
        StructureTest tst = new TunnelIdTest();
        lease.setTunnelId((TunnelId)tst.createDataStructure());

        lease.getNumSuccess();
        lease.getNumFailure();
    }
    */

    @Test
    public void testExpiration() throws Exception{
        Lease lease = new Lease();
        assertTrue(lease.isExpired());

        lease.setEndDate(new Date(1000*60*2));
        byte h[] = new byte[Hash.HASH_LENGTH];
        lease.setGateway(new Hash(h));
        StructureTest tst = new TunnelIdTest();
        lease.setTunnelId((TunnelId)tst.createDataStructure());

        assertTrue(lease.isExpired());
    }

    @Test
    public void failsWriteWithNullTunnelId() throws Exception{
        Lease lease = new Lease();
        lease.setEndDate(new Date(1000*60*2));
        byte h[] = new byte[Hash.HASH_LENGTH];
        lease.setGateway(new Hash(h));
        lease.setTunnelId(null);

        exception.expect(DataFormatException.class);
        exception.expectMessage("Not enough data to write out a Lease");
        lease.writeBytes(new ByteArrayOutputStream());
    }

    @Test
    public void failsWriteWithNullGateway() throws Exception{
        Lease lease = new Lease();
        lease.setEndDate(new Date(1000*60*2));
        byte h[] = new byte[Hash.HASH_LENGTH];
        lease.setGateway(null);
        StructureTest tst = new TunnelIdTest();
        lease.setTunnelId((TunnelId)tst.createDataStructure());

        exception.expect(DataFormatException.class);
        exception.expectMessage("Not enough data to write out a Lease");
        lease.writeBytes(new ByteArrayOutputStream());
    }

    @Test
    public void testNullEquals() throws Exception{
        Lease lease = new Lease();
        lease.setEndDate(new Date(1000*60*2));
        byte h[] = new byte[Hash.HASH_LENGTH];
        lease.setGateway(new Hash(h));
        lease.setTunnelId(null);
        assertFalse(lease.equals(null));
    }
}
