/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * A naming service based on the "hosts.txt" file.
 */
public class HostsTxtNamingService extends NamingService {

    /**
     * If this system property is specified, the tunnel will read the
     * given file for hostname=destKey values when resolving names
     */
    public final static String PROP_HOSTS_FILE = "i2p.hostsfile";

    /** default hosts.txt filename */
    public final static String DEFAULT_HOSTS_FILE = "hosts.txt";

    private final static Log _log = new Log(HostsTxtNamingService.class);

    public Destination lookup(String hostname) {
        // Try to look it up in hosts.txt 
        // Reload file each time to catch changes.
        // (and it's easier :P
        String hostsfile = System.getProperty(PROP_HOSTS_FILE, DEFAULT_HOSTS_FILE);
        Properties hosts = new Properties();
        FileInputStream fis = null;
        try {
            File f = new File(hostsfile);
            if (f.canRead()) {
                fis = new FileInputStream(f);
                hosts.load(fis);
            } else {
                _log.error("Hosts file " + hostsfile + " does not exist.");
            }
        } catch (Exception ioe) {
            _log.error("Error loading hosts file " + hostsfile, ioe);
        } finally {
            if (fis != null) try {
                fis.close();
            } catch (IOException ioe) {
            }
        }
        String res = hosts.getProperty(hostname);
        // If we can't find name in hosts, assume it's a key.
        if ((res == null) || (res.trim().length() == 0)) {
            res = hostname;
        }
        return lookupBase64(res);
    }

    public String reverseLookup(Destination dest) {
        return null;
    }
}