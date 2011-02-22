package net.i2p.client.naming;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;

/**
 * A naming service of multiple naming services.
 * Supports .b32.i2p and {b64} lookups.
 * Supports caching.
 */
public class MetaNamingService extends DummyNamingService {
    
    private final static String PROP_NAME_SERVICES = "i2p.nameservicelist";
    private final static String DEFAULT_NAME_SERVICES = 
        "net.i2p.client.naming.HostsTxtNamingService";

    protected final List<NamingService> _services;
    
    public MetaNamingService(I2PAppContext context) {
        super(context);
        String list = _context.getProperty(PROP_NAME_SERVICES, DEFAULT_NAME_SERVICES);
        StringTokenizer tok = new StringTokenizer(list, ",");
        _services = new CopyOnWriteArrayList();
        while (tok.hasMoreTokens()) {
            try {
                Class cls = Class.forName(tok.nextToken());
                Constructor con = cls.getConstructor(new Class[] { I2PAppContext.class });
                addNamingService((NamingService)con.newInstance(new Object[] { context }), false);
            } catch (Exception ex) {
            }
        }
    }
    
    /**
     *  @param if non-null, services to be added. If null, this will only handle b32 and b64.
     *  @since 0.8.5
     */
    public MetaNamingService(I2PAppContext context, List<NamingService> services) {
        super(context);
        _services = new CopyOnWriteArrayList();
        if (services != null) {
            for (NamingService ns : services) {
                addNamingService(ns, false);
            }
        }
    }
    
    @Override
    public boolean addNamingService(NamingService ns, boolean head) {
        if (head)
            _services.add(0, ns);
        else
            _services.add(ns);
        return true;
    }

    @Override
    public List<NamingService> getNamingServices() {
        return new Collections.unmodifiableList(_services);
    }

    @Override
    public boolean removeNamingService(NamingService ns) {
        return  _services.remove(ns);
    }

    @Override
    public void registerListener(NamingServiceListener nsl) {
        for (NamingService ns : _services) { 
            ns.registerListener(nsl);
        }
    }

    @Override
    public void unregisterListener(NamingServiceListener nsl) {
        for (NamingService ns : _services) { 
            ns.unregisterListener(nsl);
        }
    }

    @Override
    public Destination lookup(String hostname, Properties lookupOptions, Properties storedOptions) {
        Destination d = super.lookup(hostname, null, null);
        if (d != null)
            return d;

        for (NamingService ns : _services) { 
            d = ns.lookup(hostname, lookupOptions, storedOptions);
            if (d != null) {
                putCache(hostname, d);
                return d;
            }
        }
        return null;
    }
    
    @Override
    public String reverseLookup(Destination dest, Properties options) {
        for (NamingService ns : _services) { 
            String host = ns.reverseLookup(dest, options);
            if (host != null) {
                return host;
            }
        }
        return null;
    }

    /**
     *  Stores in the last service
     */
    @Override
    public boolean put(String hostname, Destination d, Properties options) {
        if (_services.isEmpty())
            return false;
        boolean rv = _services.get(_services.size() - 1).put(hostname, d, options);
        // overwrite any previous entry in case it changed
        if (rv)
            putCache(hostname, d);
        return rv;
    }

    /**
     *  Stores in the last service
     */
    @Override
    public boolean putIfAbsent(String hostname, Destination d, Properties options) {
        if (_services.isEmpty())
            return false;
        return _services.get(_services.size() - 1).putIfAbsent(hostname, d, options);
    }

    /**
     *  Removes from all services
     */
    @Override
    public boolean remove(String hostname, Properties options) {
        boolean rv = false;
        for (NamingService ns : _services) { 
            if (ns.remove(hostname, options))
                rv = true;
        }
        if (rv)
            removeCache(hostname);
        return rv;
    }
}
