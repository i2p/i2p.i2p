package net.i2p.client.naming;

import java.util.Collection;
import java.util.Arrays;
import java.util.Properties;
import java.util.Iterator;
import java.io.*;

import net.i2p.I2PAppContext;
import net.i2p.data.Address;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

public class FilesystemAddressDB extends AddressDB {

    public final static String PROP_ADDRESS_DIR = "i2p.addressdir";
    public final static String DEFAULT_ADDRESS_DIR = "addressDb";
    private final static Log _log = new Log(FilesystemAddressDB.class);
    
    public FilesystemAddressDB(I2PAppContext context) {
        super(context);
        
        //If the address db directory doesn't exist, create it, using the 
        //contents of hosts.txt.
        String dir = _context.getProperty(PROP_ADDRESS_DIR, DEFAULT_ADDRESS_DIR);
        File addrDir = new File(dir);
        if (!addrDir.exists()) {
            addrDir.mkdir();
            Properties hosts = new Properties();
            File hostsFile = new File("hosts.txt");
            if (hostsFile.exists() && hostsFile.canRead()) {
                try {
                    DataHelper.loadProps(hosts, hostsFile);
                } catch (IOException ioe) {
                    _log.error("Error loading hosts file " + hostsFile, ioe);
                }
            }
            Iterator iter = hosts.keySet().iterator();
            while (iter.hasNext()) {
                String hostname = (String)iter.next();
                Address addr = new Address();
                addr.setHostname(hostname);
                addr.setDestination(hosts.getProperty(hostname));
                put(addr);
            }
        }
    }

    public Address get(String hostname) {
        String dir = _context.getProperty(PROP_ADDRESS_DIR, DEFAULT_ADDRESS_DIR);
        File f = new File(dir, hostname);
        if (f.exists() && f.canRead()) {
            Address addr = new Address();
            try {
                addr.readBytes(new FileInputStream(f));
            } catch (FileNotFoundException exp) {
                return null;
            } catch (DataFormatException exp) {
                _log.error(f.getPath() + " is not a valid address file.");
                return null;
            } catch (IOException exp) {
                _log.error("Error reading " + f.getPath());
                return null;
            }
            return addr;
        } else {
            _log.warn(f.getPath() + " does not exist.");
            return null;
        }
    }

    public Address put(Address address) {
        Address previous = get(address.getHostname());
        
        String dir = _context.getProperty(PROP_ADDRESS_DIR, DEFAULT_ADDRESS_DIR);
        File f = new File(dir, address.getHostname());
        try {
            address.writeBytes(new FileOutputStream(f));
        } catch (Exception exp) {
            _log.error("Error writing " + f.getPath(), exp);
        }
        return previous;
    }

    public Address remove(String hostname) {
        Address previous = get(hostname);
        
        String dir = _context.getProperty(PROP_ADDRESS_DIR, DEFAULT_ADDRESS_DIR);       
        File f = new File(dir, hostname);
        f.delete();
        return previous;
    }

    public Address remove(Address address) {
        if (contains(address)) {
            return remove(address.getHostname());
        } else {
            return null;
        }
    }

    public boolean contains(Address address) {
        Address inDb = get(address.getHostname());
        return inDb.equals(address);
    }

    public boolean contains(String hostname) {
        return hostnames().contains(hostname);
    }

    public Collection hostnames() {
        String dir = _context.getProperty(PROP_ADDRESS_DIR, DEFAULT_ADDRESS_DIR);
        File f = new File(dir);
        return Arrays.asList(f.list());
    }

}
