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
import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.StructureTest;
import net.i2p.data.TestData;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.util.Log;

/**
 * Test harness for loading / storing DeliveryInstructions objects
 *
 * @author jrandom
 */
class DeliveryInstructionsTest extends StructureTest {
    private final static Log _log = new Log(DeliveryInstructionsTest.class);
    static {
        TestData.registerTest(new DeliveryInstructionsTest(), "DeliveryInstructions");
    }
    public DataStructure createDataStructure() throws DataFormatException {
        DeliveryInstructions instructions = new DeliveryInstructions();
	instructions.setDelayRequested(true);
	instructions.setDelaySeconds(42);
	instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_TUNNEL);
	instructions.setEncrypted(true);
	SessionKey key = new SessionKey();
	byte keyData[] = new byte[SessionKey.KEYSIZE_BYTES];
	for (int i = 0; i < keyData.length; i++) 
	    keyData[i] = (byte)i;
	key.setData(keyData);
	instructions.setEncryptionKey(key);
	Hash hash = new Hash();
	byte hashData[] = new byte[32];
	for (int i = 0; i < hashData.length; i++)
	    hashData[i] = (byte)(i%32);
	hash.setData(hashData);
	instructions.setRouter(hash);
	TunnelId id = new TunnelId();
	id.setTunnelId(666);
	instructions.setTunnelId(id);
	_log.debug("Instructions created: " + instructions + "\nBase 64: " + instructions.toBase64());
        return instructions;
    }
    public DataStructure createStructureToRead() { return new DeliveryInstructions(); }
}
