package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.StructureTest;
import net.i2p.data.TunnelId;

import org.junit.Test;

/**
 * Test harness for loading / storing DeliveryInstructions objects
 *
 * @author jrandom
 */
public class DeliveryInstructionsTest extends StructureTest {

    /**
     *  Override because DI doesn't support input/output streams any more
     */
    @Override
    @Test
    public void testStructure() throws Exception{
        DeliveryInstructions orig = (DeliveryInstructions) createDataStructure();
        byte[] temp = new byte[100];
        int len = orig.writeBytes(temp, 0);

        DeliveryInstructions ds = (DeliveryInstructions) createStructureToRead();
        ds.readBytes(temp, 0);
        byte[] temp2 = new byte[100];
        int len2 = ds.writeBytes(temp2, 0);
        assert(len2 == len);
        assert(DataHelper.eq(temp, 0, temp2, 0, len));
    }

    public DataStructure createDataStructure() throws DataFormatException {
        DeliveryInstructions instructions = new DeliveryInstructions();
		//instructions.setDelayRequested(true);
		//instructions.setDelaySeconds(42);
		instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_TUNNEL);
                // encryption key read/write disabled
		//instructions.setEncrypted(true);
		//SessionKey key = new SessionKey();
		//byte keyData[] = new byte[SessionKey.KEYSIZE_BYTES];
		//for (int i = 0; i < keyData.length; i++) 
		//    keyData[i] = (byte)i;
		//key.setData(keyData);
		//instructions.setEncryptionKey(key);
		Hash hash = new Hash();
		byte hashData[] = new byte[32];
		for (int i = 0; i < hashData.length; i++)
		    hashData[i] = (byte)(i%32);
		hash.setData(hashData);
		instructions.setRouter(hash);
		TunnelId id = new TunnelId();
		id.setTunnelId(666);
		instructions.setTunnelId(id);
		
		return instructions;
    }
    public DataStructure createStructureToRead() { return new DeliveryInstructions(); }
}
