/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Naming services create a subclass of this class.
 */
public abstract class NamingService {

    private final static Log _log = new Log(NamingService.class);

    private static final String PROP_IMPL = "i2p.naming.impl";
    private static final String DEFAULT_IMPL=
	"net.i2p.client.naming.HostsTxtNamingService";
    
    /**
     * Look up a host name.
     * @return the Destination for this host name, or
     * <code>null</code> if name is unknown.
     */
    public abstract Destination lookup(String hostname);

    /**
     * Reverse look up a destination
     * @return a host name for this Destination, or <code>null</code>
     * if none is known. It is safe for subclasses to always return
     * <code>null</code> if no reverse lookup is possible.
     */
    public abstract String reverseLookup(Destination dest);

    /**
     * Check if host name is valid Base64 encoded dest and return this
     * dest in that case. Useful as a "fallback" in custom naming
     * implementations.
     */
    protected Destination lookupBase64(String hostname) {
	try {
	    Destination result = new Destination();
	    result.fromBase64(hostname);
	    return result;
	} catch (DataFormatException dfe) {
	    if (_log.shouldLog(Log.WARN))
		_log.warn("Error translating [" + hostname + "]", dfe);
	    return null;
	}
    }

    private static NamingService instance = null;
    
    /**
     * Get a naming service instance. This method ensures that there
     * will be only one naming service instance (singleton) as well as
     * choose the implementation from the "i2p.naming.impl" system
     * property.
     */
    public static synchronized NamingService getInstance() {
	if (instance == null) {
	    String impl = System.getProperty(PROP_IMPL,
					     DEFAULT_IMPL);
	    try {
		instance = (NamingService) Class.forName(impl).newInstance();
	    } catch (Exception ex) {
		_log.error("Cannot loadNaming service implementation", ex);
		instance = new DummyNamingService(); // fallback
	    }
	}
	return instance;
    }
}
