package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

import net.i2p.util.Log;

import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.data.DataFormatException;
import net.i2p.data.SessionKey;

/**
 * Contains the session key used by the owner/creator of the tunnel to modify
 * its operational settings.
 *
 * @author jrandom
 */
public class TunnelConfigurationSessionKey extends DataStructureImpl {
    private final static Log _log = new Log(TunnelConfigurationSessionKey.class);
    private SessionKey _key;
    
    public TunnelConfigurationSessionKey() { setKey(null); }
    
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
    
    public boolean equals(Object obj) {
        if ( (obj == null) || !(obj instanceof TunnelConfigurationSessionKey))
            return false;
	return DataHelper.eq(getKey(), ((TunnelConfigurationSessionKey)obj).getKey());
    }
    
    public int hashCode() {
	if (_key == null) return 0;
        return getKey().hashCode(); 
    }
    
    public String toString() {
        return "[TunnelConfigurationSessionKey: " + getKey() + "]";
    }
}
