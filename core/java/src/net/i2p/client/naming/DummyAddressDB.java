package net.i2p.client.naming;

import java.util.Collection;

import net.i2p.I2PAppContext;
import net.i2p.data.Address;

/**
 *  @deprecated unused
 */
public class DummyAddressDB extends AddressDB {

    public DummyAddressDB(I2PAppContext context) {
        super(context);
    }
    
    @Override
    public Address get(String hostname) {
        return null;
    }
    
    @Override
    public Address put(Address address) {
        return null;
    }
    
    @Override
    public Address remove(String hostname) {
        return null;
    }
    
    @Override
    public Address remove(Address address) {
        return null;
    }
    
    @Override
    public boolean contains(Address address) {
        return false;
    }
    
    @Override
    public boolean contains(String hostname) {
        return false;
    }
    
    @Override
    public Collection hostnames() {
        return null;
    }

}
