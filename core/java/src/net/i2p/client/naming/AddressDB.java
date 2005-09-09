package net.i2p.client.naming;

import java.lang.reflect.Constructor;
import java.util.Collection;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import net.i2p.data.Address;

public abstract class AddressDB {
    
    private final static Log _log = new Log(NamingService.class);
    protected I2PAppContext _context;
    
    /** what classname should be used as the address db impl? */
    public static final String PROP_IMPL = "i2p.addressdb.impl";
    private static final String DEFAULT_IMPL = "net.i2p.client.naming.FilesystemAddressDB";
    
    /** 
     * The address db should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    protected AddressDB(I2PAppContext context) {
        _context = context;
    }
    
    private AddressDB() { // nop
    }
    
    /**
     * Get an address db instance. This method ensures that there
     * will be only one address db instance (singleton) as well as
     * choose the implementation from the "i2p.addressdb.impl" system
     * property.
     */
    public static final synchronized AddressDB createInstance(I2PAppContext context) {
        AddressDB instance = null;
        String impl = context.getProperty(PROP_IMPL, DEFAULT_IMPL);
        try {
            Class cls = Class.forName(impl);
            Constructor con = cls.getConstructor(new Class[] { I2PAppContext.class });
            instance = (AddressDB)con.newInstance(new Object[] { context });
        } catch (Exception ex) {
            _log.error("Cannot load address db implementation", ex);
            instance = new DummyAddressDB(context); // fallback
        }
        return instance;
    }
    
    public abstract Address get(String hostname);
    public abstract Address put(Address address);
    public abstract Address remove(String hostname);
    public abstract Address remove(Address address);
    public abstract boolean contains(Address address);
    public abstract boolean contains(String hostname);
    public abstract Collection hostnames();
}
