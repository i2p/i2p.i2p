/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import net.i2p.data.Destination;

/**
 * A Dummy naming service that can only handle base64 destinations.
 */
class DummyNamingService extends NamingService {
    public Destination lookup(String hostname) {
	return lookupBase64(hostname);
    }
	
    public String reverseLookup(Destination dest) {
	return null;
    }
}
