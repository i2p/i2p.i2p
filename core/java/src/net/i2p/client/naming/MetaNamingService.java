package net.i2p.client.naming;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;

public class MetaNamingService extends NamingService {
    
    private final static String PROP_NAME_SERVICES = "i2p.nameservicelist";
    private final static String DEFAULT_NAME_SERVICES = 
        "net.i2p.client.naming.PetNameNamingService,net.i2p.client.naming.HostsTxtNamingService";
    private List _services;
    
    public MetaNamingService(I2PAppContext context) {
        super(context);
        
        String list = _context.getProperty(PROP_NAME_SERVICES, DEFAULT_NAME_SERVICES);
        StringTokenizer tok = new StringTokenizer(list, ",");
        _services = new ArrayList(tok.countTokens());
        while (tok.hasMoreTokens()) {
            try {
                Class cls = Class.forName(tok.nextToken());
                Constructor con = cls.getConstructor(new Class[] { I2PAppContext.class });
                _services.add(con.newInstance(new Object[] { context }));
            } catch (Exception ex) {
                _services.add(new DummyNamingService(context)); // fallback
            }
        }
    }
    
    public Destination lookup(String hostname) {
        Iterator iter = _services.iterator();
        while (iter.hasNext()) {
            NamingService ns = (NamingService)iter.next();
            Destination dest = ns.lookup(hostname);
            if (dest != null) {
                return dest;
            }
        }
        return lookupBase64(hostname);
    }

    public String reverseLookup(Destination dest) {
        Iterator iter = _services.iterator();
        while (iter.hasNext()) {
            NamingService ns = (NamingService)iter.next();
            String hostname = ns.reverseLookup(dest);
            if (hostname != null) {
                return hostname;
            }
        }
        return null;
    }

}
