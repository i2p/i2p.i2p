package net.i2p.client.naming;

import java.util.Collection;

import net.i2p.I2PAppContext;
import net.i2p.data.Address;

public class DummyAddressDB extends AddressDB {

    public DummyAddressDB(I2PAppContext context) {
        super(context);
    }

    public Address get(String hostname) {
        return null;
    }

    public Address put(Address address) {
        return null;
    }

    public Address remove(String hostname) {
        return null;
    }

    public Address remove(Address address) {
        return null;
    }

    public boolean contains(Address address) {
        return false;
    }

    public boolean contains(String hostname) {
        return false;
    }
    
    public Collection hostnames() {
        return null;
    }

}
