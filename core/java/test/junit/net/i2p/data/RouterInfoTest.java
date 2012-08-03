package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.Properties;

import net.i2p.crypto.KeyGenerator;
import net.i2p.util.Log;

/**
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
public class RouterInfoTest extends StructureTest {
    private final static Log _log = new Log(RouterInfoTest.class);
    public DataStructure createDataStructure() throws DataFormatException {
        RouterInfo info = new RouterInfo();
        HashSet addresses = new HashSet();
        DataStructure structure = (new RouterAddressTest()).createDataStructure();
        addresses.add(structure);
        info.setAddresses(addresses);
        
    	PublicKey pubKey = null;
    	SigningPublicKey signingPubKey = null;
    	PrivateKey privKey = null;
    	SigningPrivateKey signingPrivKey = null;
    	
    	Object obj[] = KeyGenerator.getInstance().generatePKIKeypair();
    	pubKey = (PublicKey)obj[0];
    	privKey = (PrivateKey)obj[1];
    	obj = KeyGenerator.getInstance().generateSigningKeypair();
    	signingPubKey = (SigningPublicKey)obj[0];
    	signingPrivKey = (SigningPrivateKey)obj[1];
    	
    	_log.debug("SigningPublicKey: " + signingPubKey);
    	_log.debug("SigningPrivateKey: " + signingPrivKey);
    	
    	RouterIdentity ident = new RouterIdentity();
    	ident.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
    	ident.setPublicKey(pubKey);
    	ident.setSigningPublicKey(signingPubKey);
    	
        info.setIdentity(ident);
        
        Properties options = new Properties();
    	for (int i = 0; i < 16; i++) {
    	    options.setProperty("option." + i, "12345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890$:." + i);
    	}
        options.setProperty("netConnectionSpeed", "OC12");
        info.setOptions(options);
	
        HashSet peers = new HashSet();
        structure = (new HashTest()).createDataStructure();
        peers.add(structure);
        info.setPeers(peers);
    	info.setPublished(System.currentTimeMillis());
            
            //info.setVersion(69);
            
    	info.sign(signingPrivKey);
            
        return info;
    }
    public DataStructure createStructureToRead() { return new RouterInfo(); }
}
