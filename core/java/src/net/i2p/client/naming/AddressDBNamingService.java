package net.i2p.client.naming;

import java.util.Iterator;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.data.Address;

public class AddressDBNamingService extends NamingService {
    
    private AddressDB _addressdb;
    
    public AddressDBNamingService(I2PAppContext context) {
        super(context);
        _addressdb = AddressDB.createInstance(context);
    }
    
    private AddressDBNamingService() {
        super(null);
    }

    public Destination lookup(String hostname) {
        Address addr = _addressdb.get(hostname);
        if (addr != null) {
            return addr.getDestination();
        } else {
            // If we can't find hostname in the addressdb, assume it's a key.
            return lookupBase64(hostname);
        }
    }

    public String reverseLookup(Destination dest) {
        Iterator iter = _addressdb.hostnames().iterator();
        while (iter.hasNext()) {
            Address addr = _addressdb.get((String)iter.next());
            if (addr != null && addr.getDestination().equals(dest)) {
                return addr.getHostname();
            }
        }
        return null;        
    }
}
