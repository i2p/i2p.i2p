/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import java.lang.reflect.Constructor;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Naming services create a subclass of this class.
 */
public abstract class NamingService {

    private final static Log _log = new Log(NamingService.class);
    protected I2PAppContext _context;

    /** what classname should be used as the naming service impl? */
    public static final String PROP_IMPL = "i2p.naming.impl";
    private static final String DEFAULT_IMPL = "net.i2p.client.naming.MetaNamingService";

    
    /** 
     * The naming service should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    protected NamingService(I2PAppContext context) {
        _context = context;
    }
    private NamingService() { // nop
    }
    
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
            if (_log.shouldLog(Log.WARN)) _log.warn("Error translating [" + hostname + "]", dfe);
            return null;
        }
    }

    /**
     * Get a naming service instance. This method ensures that there
     * will be only one naming service instance (singleton) as well as
     * choose the implementation from the "i2p.naming.impl" system
     * property.
     */
    public static final synchronized NamingService createInstance(I2PAppContext context) {
        NamingService instance = null;
        String impl = context.getProperty(PROP_IMPL, DEFAULT_IMPL);
        try {
            Class cls = Class.forName(impl);
            Constructor con = cls.getConstructor(new Class[] { I2PAppContext.class });
            instance = (NamingService)con.newInstance(new Object[] { context });
        } catch (Exception ex) {
            _log.error("Cannot loadNaming service implementation", ex);
            instance = new DummyNamingService(context); // fallback
        }
        return instance;
    }
}