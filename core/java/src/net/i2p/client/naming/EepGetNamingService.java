/*
 * By zzz 2008, released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */
package net.i2p.client.naming;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.util.EepGet;

/**
 * A network-based naming service using HTTP, with in-memory caching.
 * Fetches from one or more remote (in-i2p) CGI services using HTTP GET.
 *
 * The remote HTTP service takes a CGI parameter and must return (only) the
 * 516-byte Base64 destination, or hostname=dest.
 * A trailing \n or \r\n is acceptable.
 *
 * Should be used from MetaNamingService, after HostsTxtNamingService.
 * Cannot be used as the only NamingService! Be sure any naming service hosts
 * are in hosts.txt.
 * Supports caching, b32, and b64.
 *
 * Sample config to put in configadvanced.jsp (restart required):
 *
 * i2p.naming.impl=net.i2p.client.naming.MetaNamingService
 * i2p.nameservicelist=net.i2p.client.naming.HostsTxtNamingService,net.i2p.client.naming.EepGetNamingService
 * i2p.naming.eepget.list=http://namingservice.i2p/cgi-bin/lkup.cgi?host=,http://i2host.i2p/cgi-bin/i2hostquery?
 *
 */
public class EepGetNamingService extends DummyNamingService {

    private final static String PROP_EEPGET_LIST = "i2p.naming.eepget.list";
    private final static String DEFAULT_EEPGET_LIST = "http://i2host.i2p/cgi-bin/i2hostquery?";

    /** 
     * The naming service should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    public EepGetNamingService(I2PAppContext context) {
        super(context);
    }
    
    private List getURLs() {
        String list = _context.getProperty(PROP_EEPGET_LIST, DEFAULT_EEPGET_LIST);
        StringTokenizer tok = new StringTokenizer(list, ",");
        List rv = new ArrayList(tok.countTokens());
        while (tok.hasMoreTokens())
            rv.add(tok.nextToken());
        return rv;
    }
    
    @Override
    public Destination lookup(String hostname, Properties lookupOptions, Properties storedOptions) {
        Destination d = super.lookup(hostname, null, null);
        if (d != null)
            return d;

        hostname = hostname.toLowerCase(Locale.US);
        // Base32 failed?
        if (hostname.length() == BASE32_HASH_LENGTH + 8 && hostname.endsWith(".b32.i2p"))
            return null;

        List URLs = getURLs();
        if (URLs.isEmpty())
            return null;

        // prevent lookup loops - this cannot be the only lookup service
        for (int i = 0; i < URLs.size(); i++) { 
            String url = (String)URLs.get(i);
            if (url.startsWith("http://" + hostname + "/")) {
                _log.error("Lookup loop: " + hostname);
                return null;
            }
        }

        // lookup
        for (int i = 0; i < URLs.size(); i++) { 
            String url = (String)URLs.get(i);
            String key = fetchAddr(url, hostname);	  	
            if (key != null) {
                _log.error("Success: " + url + hostname);
                d = lookupBase64(key);
                putCache(hostname, d);
                return d;
            }
        }
        return null;
    }

    // FIXME allow larger Dests for non-null Certs
    private static final int MAX_RESPONSE = DEST_SIZE + 68 + 10; // allow for hostname= and some trailing stuff
    private String fetchAddr(String url, String hostname) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(MAX_RESPONSE);
        
        try {
            // Do a proxied eepget into our ByteArrayOutputStream with 0 retries
            EepGet get = new EepGet(_context, true, "localhost", 4444, 0, DEST_SIZE, MAX_RESPONSE,
                                    null, baos, url + hostname, false, null, null);
            // 10s header timeout, 15s total timeout, unlimited inactivity timeout
            if (get.fetch(10*1000l, 15*1000l, -1l)) {
                if (baos.size() < DEST_SIZE) {
                    _log.error("Short response: " + url + hostname);
                    return null;
                }
                String key = baos.toString();
                if (key.startsWith(hostname + "="))  // strip hostname=
                    key = key.substring(hostname.length() + 1); 
                key = key.substring(0, DEST_SIZE);   // catch IndexOutOfBounds exception below
                if (!key.endsWith("AA")) {
                    _log.error("Invalid key: " + url + hostname);
                    return null;
                }
                if (key.replaceAll("[a-zA-Z0-9~-]", "").length() != 0) {
                    _log.error("Invalid chars: " + url + hostname);
                    return null;
                }
                return key;
            }
            _log.error("Fetch failed from: " + url + hostname);
            return null;
        } catch (Throwable t) {
            _log.error("Error fetching the addr", t);
        }
        _log.error("Caught from: " + url + hostname);
        return null;
    }
}
