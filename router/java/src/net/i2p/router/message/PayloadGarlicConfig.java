package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.i2np.I2NPMessage;

/**
 * Garlic config containing an I2NP message
 *
 */
public class PayloadGarlicConfig extends GarlicConfig {
    private I2NPMessage _payload;

    public PayloadGarlicConfig() {
	super();
    }
    
    /**
     * Specify the I2NP message to be sent - if this is set, no other cloves can be included
     * in this block
     */
    public void setPayload(I2NPMessage message) { 
	_payload = message; 
	if (message != null)
	    clearCloves();
    }
    public I2NPMessage getPayload() { return _payload; }
 
    @Override
    protected String getSubData() { 
	StringBuilder buf = new StringBuilder();
	buf.append("<payloadMessage>").append(_payload).append("</payloadMessage>");
	return buf.toString(); 
    }
}
