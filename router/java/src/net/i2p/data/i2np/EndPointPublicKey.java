package net.i2p.data.i2np;
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
import net.i2p.data.PublicKey;
import net.i2p.util.Log;

/**
 * Contains the public key which matches the EndPointPrivateKey.  This is 
 * published on the LeaseSet and used to encrypt messages to the router to
 * which a Destination is currently connected.
 *
 * @author jrandom
 */
public class EndPointPublicKey extends DataStructureImpl {
    private final static Log _log = new Log(EndPointPublicKey.class);
    private PublicKey _key;
    
    public EndPointPublicKey() { setKey(null); }
    
    public PublicKey getKey() { return _key; }
    public void setKey(PublicKey key) { _key= key; }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
	_key = new PublicKey();
        _key.readBytes(in);
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_key == null) throw new DataFormatException("Invalid key");
	_key.writeBytes(out);
    }
    
    @Override
    public boolean equals(Object obj) {
        if ( (obj == null) || !(obj instanceof EndPointPublicKey))
            return false;
	return DataHelper.eq(getKey(), ((EndPointPublicKey)obj).getKey());
    }
    
    @Override
    public int hashCode() {
	if (_key == null) return 0;
        return getKey().hashCode(); 
    }
    
    @Override
    public String toString() {
        return "[EndPointPublicKey: " + getKey() + "]";
    }
}
