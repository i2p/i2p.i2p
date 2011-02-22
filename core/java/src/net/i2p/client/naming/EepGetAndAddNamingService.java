/*
 * public domain 
 * with no warranty of any kind, either expressed or implied.  
 */
package net.i2p.client.naming;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;

/**
 * Simple extension to EepGetNamingService to append what we find to hosts.txt,
 * so we only have to do an EepGet query once.
 *
 * Note that there is little protection - there are a lot of validity checks in addressbook that are
 * not included here.
 *
 * MUST be used from MetaNamingService, after HostsTxtNamingService.
 * Cannot be used as the only NamingService! Be sure any naming service hosts
 * are in hosts.txt. If this isn't after HostsTxtNamingService, you will
 * clog up your hosts.txt with duplicate entries.
 *
 * Sample config to put in configadvanced.jsp (restart required):
 *
 * i2p.naming.impl=net.i2p.client.naming.MetaNamingService
 * i2p.nameservicelist=net.i2p.client.naming.HostsTxtNamingService,net.i2p.client.naming.EepGetAndAddNamingService
 * i2p.naming.eepget.list=http://stats.i2p/cgi-bin/hostquery.cgi?a=,http://i2host.i2p/cgi-bin/i2hostquery?
 *
 * @author zzz
 * @deprecated use HostsTxtNamingService.put()
 * @since 0.7.9
 */
public class EepGetAndAddNamingService extends EepGetNamingService {

    /** default hosts.txt filename */
    private final static String DEFAULT_HOSTS_FILE = "hosts.txt";

    public EepGetAndAddNamingService(I2PAppContext context) {
        super(context);
    }
    
    @Override
    public Destination lookup(String hostname) {
        Destination rv = super.lookup(hostname);
        if (rv != null) {
            hostname = hostname.toLowerCase();
            // If it's long, assume it's a key.
            if (hostname.length() < 516 && hostname.endsWith(".i2p") && ! hostname.endsWith(".b32.i2p")) {
                File f = new File(_context.getRouterDir(), DEFAULT_HOSTS_FILE);
                if ( (f.exists()) && (f.canWrite()) ) {
                    synchronized(this) {
                        FileOutputStream fos = null;
                        try {
                            fos = new FileOutputStream(f, true);
                            String line = hostname + '=' + rv.toBase64() + System.getProperty("line.separator");
                            fos.write(line.getBytes());
                        } catch (IOException ioe) {
                            System.err.println("Error appending: " + ioe);
                        } finally {
                            if (fos != null) try { fos.close(); } catch (IOException cioe) {}
                        }
                    }
                }
            }
        }
        return rv;
    }
}
