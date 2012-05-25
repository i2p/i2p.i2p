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
import net.i2p.data.SessionKey;
import net.i2p.util.Log;

/**
 * Contains the session key used by the tunnel gateway to encrypt the DeliveryInstructions
 * and used by the tunnel end point to decrypt those instructions.
 *
 * @author jrandom
 */
public class TunnelSessionKey extends DataStructureImpl {
    private final static Log _log = new Log(TunnelSessionKey.class);
    private SessionKey _key;
    
    public TunnelSessionKey() { this(null); }
    public TunnelSessionKey(SessionKey key) { setKey(key); }
    
    public SessionKey getKey() { return _key; }
    public void setKey(SessionKey key) { _key= key; }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
	_key = new SessionKey();
        _key.readBytes(in);
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_key == null) throw new DataFormatException("Invalid key");
	_key.writeBytes(out);
    }
    
    @Override
    public boolean equals(Object obj) {
        if ( (obj == null) || !(obj instanceof TunnelSessionKey))
            return false;
	return DataHelper.eq(getKey(), ((TunnelSessionKey)obj).getKey());
    }
    
    @Override
    public int hashCode() {
	if (_key == null) return 0;
        return getKey().hashCode(); 
    }
    
    @Override
    public String toString() {
        return "[TunnelSessionKey: " + getKey() + "]";
    }
}
