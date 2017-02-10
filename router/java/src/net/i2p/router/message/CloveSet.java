package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Certificate;
import net.i2p.data.i2np.GarlicClove;

/**
 * Wrap up the data contained in a GarlicMessage after being decrypted
 *
 */
class CloveSet {
    private final GarlicClove[] _cloves;
    private final Certificate _cert;
    private final long _msgId;
    private final long _expiration;
    
    /**
     *  @param cloves non-null, all entries non-null
     *  @param cert non-null
     */
    public CloveSet(GarlicClove[] cloves, Certificate cert, long msgId, long expiration) {
	_cloves = cloves;
        _cert = cert;
	_msgId = msgId;
	_expiration = expiration;
    }
    
    public int getCloveCount() { return _cloves.length; }

    /** @throws ArrayIndexOutOfBoundsException */
    public GarlicClove getClove(int index) { return _cloves[index]; }
    
    public Certificate getCertificate() { return _cert; }

    public long getMessageId() { return _msgId; }

    public long getExpiration() { return _expiration; }
    
    @Override
    public String toString() { 
	StringBuilder buf = new StringBuilder(128);
	buf.append("{");
	for (int i = 0; i < _cloves.length; i++) {
	    GarlicClove clove = _cloves[i];
	    if (clove.getData() != null)
		buf.append(clove.getData().getClass().getName()).append(", ");
	    else
		buf.append("[null clove], ");
	}
	buf.append("}");
	return buf.toString();
    }
}
