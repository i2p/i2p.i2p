/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * A naming service based on the "hosts.txt" file.
 */
public class HostsTxtNamingService extends NamingService {

    /** 
     * The naming service should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    public HostsTxtNamingService(I2PAppContext context) { super(context); }
    private HostsTxtNamingService() { super(null); }
    
    /**
     * If this system property is specified, the tunnel will read the
     * given file for hostname=destKey values when resolving names
     */
    public final static String PROP_HOSTS_FILE = "i2p.hostsfilelist";
    public final static String PROP_B32 = "i2p.naming.hostsTxt.useB32";

    /** default hosts.txt filename */
    public final static String DEFAULT_HOSTS_FILE = 
        "privatehosts.txt,userhosts.txt,hosts.txt";

    private final static Log _log = new Log(HostsTxtNamingService.class);

    private List getFilenames() {
        String list = _context.getProperty(PROP_HOSTS_FILE, DEFAULT_HOSTS_FILE);
        StringTokenizer tok = new StringTokenizer(list, ",");
        List rv = new ArrayList(tok.countTokens());
        while (tok.hasMoreTokens())
            rv.add(tok.nextToken());
        return rv;
    }
    
    private static final int BASE32_HASH_LENGTH = 52;   // 1 + Hash.HASH_LENGTH * 8 / 5

    @Override
    public Destination lookup(String hostname) {
        Destination d = getCache(hostname);
        if (d != null)
            return d;

        // If it's long, assume it's a key.
        if (hostname.length() >= 516) {
            d = lookupBase64(hostname);
            // What the heck, cache these too
            putCache(hostname, d);
            return d;
        }

        // Try Base32 decoding
        if (hostname.length() == BASE32_HASH_LENGTH + 8 && hostname.endsWith(".b32.i2p") &&
            Boolean.valueOf(_context.getProperty(PROP_B32, "true")).booleanValue()) {
            d = LookupDest.lookupBase32Hash(_context, hostname.substring(0, BASE32_HASH_LENGTH));
            if (d != null) {
                putCache(hostname, d);
                return d;
            }
        }

        List filenames = getFilenames();
        for (int i = 0; i < filenames.size(); i++) { 
            String hostsfile = (String)filenames.get(i);
            Properties hosts = new Properties();
            try {
                File f = new File(_context.getRouterDir(), hostsfile);
                if ( (f.exists()) && (f.canRead()) ) {
                    DataHelper.loadProps(hosts, f, true);
                    
                    String key = hosts.getProperty(hostname.toLowerCase());
                    if ( (key != null) && (key.trim().length() > 0) ) {
                        d = lookupBase64(key);
                        putCache(hostname, d);
                        return d;
                    }
                    
                } else {
                    _log.warn("Hosts file " + hostsfile + " does not exist.");
                }
            } catch (Exception ioe) {
                _log.error("Error loading hosts file " + hostsfile, ioe);
            }
            // not found, continue to the next file
        }
        return null;
    }
    
    @Override
    public String reverseLookup(Destination dest) {
        String destkey = dest.toBase64();
        List filenames = getFilenames();
        for (int i = 0; i < filenames.size(); i++) { 
            String hostsfile = (String)filenames.get(i);
            Properties hosts = new Properties();
            try {
                File f = new File(_context.getRouterDir(), hostsfile);
                if ( (f.exists()) && (f.canRead()) ) {
                    DataHelper.loadProps(hosts, f, true);
                    Set keyset = hosts.keySet();
                    Iterator iter = keyset.iterator();
                    while (iter.hasNext()) {
                        String host = (String)iter.next();
                        String key = hosts.getProperty(host);
                        if (destkey.equals(key))
                            return host;
                    }
                }
            } catch (Exception ioe) {
                _log.error("Error loading hosts file " + hostsfile, ioe);
            }
        }
        return null;
    }

    @Override
    public String reverseLookup(Hash h) {
        List filenames = getFilenames();
        for (int i = 0; i < filenames.size(); i++) { 
            String hostsfile = (String)filenames.get(i);
            Properties hosts = new Properties();
            try {
                File f = new File(_context.getRouterDir(), hostsfile);
                if ( (f.exists()) && (f.canRead()) ) {
                    DataHelper.loadProps(hosts, f, true);
                    Set keyset = hosts.keySet();
                    Iterator iter = keyset.iterator();
                    while (iter.hasNext()) {
                        String host = (String)iter.next();
                        String key = hosts.getProperty(host);
                        try {
                            Destination destkey = new Destination();
                            destkey.fromBase64(key);
                            if (h.equals(destkey.calculateHash()))
                                return host;
                        } catch (DataFormatException dfe) {}
                    }
                }
            } catch (Exception ioe) {
                _log.error("Error loading hosts file " + hostsfile, ioe);
            }
        }
        return null;
    }
}
