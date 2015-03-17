/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;

/**
 * A naming service based on multiple "hosts.txt" files.
 * Supports .b32.i2p and {b64} lookups.
 * Supports caching.
 * All host names are converted to lower case.
 */
public class HostsTxtNamingService extends MetaNamingService {

    /** 
     * The naming service should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    public HostsTxtNamingService(I2PAppContext context) {
        super(context, null);
        for (String name : getFilenames()) {
            addNamingService(new SingleFileNamingService(context, name), false);
        }
    }
    
    /**
     * If this system property is specified, the tunnel will read the
     * given file for hostname=destKey values when resolving names
     */
    public final static String PROP_HOSTS_FILE = "i2p.hostsfilelist";

    /** default hosts.txt filenames */
    public final static String DEFAULT_HOSTS_FILE = 
        "privatehosts.txt,userhosts.txt,hosts.txt";

    private List<String> getFilenames() {
        String list = _context.getProperty(PROP_HOSTS_FILE, DEFAULT_HOSTS_FILE);
        StringTokenizer tok = new StringTokenizer(list, ",");
        List<String> rv = new ArrayList(tok.countTokens());
        while (tok.hasMoreTokens())
            rv.add(tok.nextToken());
        return rv;
    }
    
    @Override
    public Destination lookup(String hostname, Properties lookupOptions, Properties storedOptions) {
        // If it's long, assume it's a key.
        if (hostname.length() >= DEST_SIZE)
            return lookupBase64(hostname);
        return super.lookup(hostname.toLowerCase(Locale.US), lookupOptions, storedOptions);
    }

    @Override
    public boolean put(String hostname, Destination d, Properties options) {
        return super.put(hostname.toLowerCase(Locale.US), d, options);
    }

    @Override
    public boolean putIfAbsent(String hostname, Destination d, Properties options) {
        return super.putIfAbsent(hostname.toLowerCase(Locale.US), d, options);
    }

    @Override
    public boolean remove(String hostname, Properties options) {
        return super.remove(hostname.toLowerCase(Locale.US), options);
    }

    /**
     *  All services aggregated, unless options contains
     *  the property "file", in which case only for that file
     */
    @Override
    public Set<String> getNames(Properties options) {
        String file = null;
        if (options != null)
            file = options.getProperty("file");
        if (file == null)
            return super.getNames(options);
        for (NamingService ns : _services) { 
             String name = ns.getName();
             if (name.equals(file) || name.endsWith('/' + file) || name.endsWith('\\' + file))
                 return ns.getNames(options);
        }
        return new HashSet(0);
    }
}
