package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Certificate;
import net.i2p.data.i2np.GarlicClove;

/**
 * Wrap up the data contained in a CloveMessage after being decrypted
 *
 */
public class CloveSet {
    private List _cloves;
    private Certificate _cert;
    private long _msgId;
    private long _expiration;
    
    public CloveSet() {
	_cloves = new ArrayList();
	_cert = null;
	_msgId = -1;
	_expiration = -1;
    }
    
    public int getCloveCount() { return _cloves.size(); }
    public void addClove(GarlicClove clove) { _cloves.add(clove); }
    public GarlicClove getClove(int index) { return (GarlicClove)_cloves.get(index); }
    
    public Certificate getCertificate() { return _cert; }
    public void setCertificate(Certificate cert) { _cert = cert; }
    public long getMessageId() { return _msgId; }
    public void setMessageId(long id) { _msgId = id; }
    public long getExpiration() { return _expiration; }
    public void setExpiration(long expiration) { _expiration = expiration; }
    
    @Override
    public String toString() { 
	StringBuilder buf = new StringBuilder(128);
	buf.append("{");
	for (int i = 0; i < _cloves.size(); i++) {
	    GarlicClove clove = (GarlicClove)_cloves.get(i);
	    if (clove.getData() != null)
		buf.append(clove.getData().getClass().getName()).append(", ");
	    else
		buf.append("[null clove], ");
	}
	buf.append("}");
	return buf.toString();
    }
}
