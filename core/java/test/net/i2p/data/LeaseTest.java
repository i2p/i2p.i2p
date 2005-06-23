package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.io.ByteArrayOutputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.Lease;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;

/**
 * Test harness for loading / storing Lease objects
 *
 * @author jrandom
 */
public class LeaseTest extends StructureTest {
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
    
    public void testNullWrite() throws Exception{
        Lease lease = new Lease();
        lease.setEndDate(new Date(1000*60*2));
        byte h[] = new byte[Hash.HASH_LENGTH];
        lease.setGateway(new Hash(h));
        lease.setTunnelId(null);
        boolean error = false;
        try{
            lease.writeBytes(new ByteArrayOutputStream());
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
        
        lease = new Lease();
        lease.setEndDate(new Date(1000*60*2));
        h = new byte[Hash.HASH_LENGTH];
        lease.setGateway(null);
        StructureTest tst = new TunnelIdTest();
        lease.setTunnelId((TunnelId)tst.createDataStructure());
        error = false;
        try{
            lease.writeBytes(new ByteArrayOutputStream());
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
    }
    
    public void testNullEquals() throws Exception{
        Lease lease = new Lease();
        lease.setEndDate(new Date(1000*60*2));
        byte h[] = new byte[Hash.HASH_LENGTH];
        lease.setGateway(new Hash(h));
        lease.setTunnelId(null);
        assertFalse(lease.equals(null));
    }
    
}
