package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;

/**
 * Wrap up the keys given to the router when a destination connects to it
 *
 */
public class LeaseSetKeys extends DataStructureImpl {
    private Destination _dest;
    private SigningPrivateKey _revocationKey;
    private PrivateKey _decryptionKey;

    public LeaseSetKeys() {
	this(null, null, null);
    }
    public LeaseSetKeys(Destination dest, SigningPrivateKey revocationKey, PrivateKey decryptionKey) {
	_dest = dest;
	_revocationKey = revocationKey;
	_decryptionKey = decryptionKey;
    }

    /**
     * Destination in question
     */
    public Destination getDestination() { return _dest; }
    /**
     * Key with which a LeaseSet can be revoked (by republishing it with no Leases)
     *
     */
    public SigningPrivateKey getRevocationKey() { return _revocationKey; }
    /**
     * Decryption key which can open up garlic messages encrypted to the 
     * LeaseSet's public key.  This is used because the general public does not
     * know on what router the destination is connected and as such can't encrypt 
     * to that router's normal public key.
     *
     */
    public PrivateKey getDecryptionKey() { return _decryptionKey; }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
	_dest = new Destination();
	_dest.readBytes(in);
	_decryptionKey = new PrivateKey();
	_decryptionKey.readBytes(in);
	_revocationKey = new SigningPrivateKey();
	_revocationKey.readBytes(in);
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_dest == null) throw new DataFormatException("Null destination");
        if (_decryptionKey == null) throw new DataFormatException("Null decryption key");
        if (_revocationKey == null) throw new DataFormatException("Null revocation key");
	_dest.writeBytes(out);
	_decryptionKey.writeBytes(out);
	_revocationKey.writeBytes(out);
    }
    
    @Override
    public int hashCode() {
	int rv = 0;
	rv += DataHelper.hashCode(_dest);
	rv += DataHelper.hashCode(_revocationKey);
	rv += DataHelper.hashCode(_decryptionKey);
	return rv;
    }
    
    @Override
    public boolean equals(Object obj) {
	if ( (obj != null) && (obj instanceof LeaseSetKeys) ) {
	    LeaseSetKeys keys = (LeaseSetKeys)obj;
	    return DataHelper.eq(getDestination(), keys.getDestination()) &&
		   DataHelper.eq(getDecryptionKey(), keys.getDecryptionKey()) &&
		   DataHelper.eq(getRevocationKey(), keys.getRevocationKey());
	} else {
	    return false;
	}
    }
}
