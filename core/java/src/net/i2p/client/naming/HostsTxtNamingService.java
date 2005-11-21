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
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
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
        
    public Destination lookup(String hostname) {
        // check the list each time, reloading the file on each
        // lookup
        
        List filenames = getFilenames();
        for (int i = 0; i < filenames.size(); i++) { 
            String hostsfile = (String)filenames.get(i);
            Properties hosts = new Properties();
            try {
                File f = new File(hostsfile);
                if ( (f.exists()) && (f.canRead()) ) {
                    DataHelper.loadProps(hosts, f, true);
                    
                    String key = hosts.getProperty(hostname.toLowerCase());
                    if ( (key != null) && (key.trim().length() > 0) ) {
                        return lookupBase64(key);
                    }
                    
                } else {
                    _log.warn("Hosts file " + hostsfile + " does not exist.");
                }
            } catch (Exception ioe) {
                _log.error("Error loading hosts file " + hostsfile, ioe);
            }
            // not found, continue to the next file
        }
        // If we can't find name in any of the hosts files, 
        // assume it's a key.
        return lookupBase64(hostname);
    }

    public String reverseLookup(Destination dest) {
        return null;
    }
}