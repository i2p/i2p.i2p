package net.i2p.client.naming;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;

public class PetNameNamingService extends NamingService {

    private PetNameDB _petnameDb;
    public final static String PROP_PETNAME_FILE = "i2p.petnamefile";
    public final static String DEFAULT_PETNAME_FILE = "petnames.txt";
    
    public PetNameNamingService(I2PAppContext context) {
        super(context);
        _petnameDb = _context.petnameDb();
        String file = _context.getProperty(PROP_PETNAME_FILE, DEFAULT_PETNAME_FILE);

        //If the petnamedb file doesn't exist, create it, using the 
        //contents of hosts.txt.
//        File nameFile = new File(file);
//        if (!nameFile.exists()) {
//            Properties hosts = new Properties();
//            File hostsFile = new File("hosts.txt");
//            if (hostsFile.exists() && hostsFile.canRead()) {
//                try {
//                    DataHelper.loadProps(hosts, hostsFile);
//                } catch (IOException ioe) {
//                }
//            }
//            Iterator iter = hosts.keySet().iterator();
//            while (iter.hasNext()) {
//                String hostname = (String)iter.next();
//                PetName pn = new PetName(hostname, "i2p", "http", hosts.getProperty(hostname));
//                _petnameDb.set(hostname, pn);
//            }
//            try {
//                _petnameDb.store(file);
//            } catch (IOException ioe) {
//            }
//        }
        
        try {
            _petnameDb.load(file);
        } catch (IOException ioe) {
        }
    }

    public Destination lookup(String hostname) {
        PetName name = _petnameDb.getByName(hostname);
        if (name != null && name.getNetwork().equalsIgnoreCase("i2p")) {
            return lookupBase64(name.getLocation());
        } else {
            return lookupBase64(hostname);
        }
    }

    public String reverseLookup(Destination dest) {
        return _petnameDb.getByLocation(dest.toBase64()).getName();
    }
}
