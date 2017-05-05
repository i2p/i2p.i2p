package net.i2p.router.naming;

import junit.framework.TestCase;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;


public class BlockfileNamingServiceTest extends TestCase {
    BlockfileNamingService _bns;
    List<String> _names;
    File hostsTxt, routerDir;

    @SuppressWarnings({ "unchecked", "rawtypes" })
	public void setUp() throws Exception {
        I2PAppContext ctx = new I2PAppContext();
        routerDir = ctx.getRouterDir();
        
        // first load the list of hosts that will be queried
        InputStream is = getClass().getResourceAsStream("/hosts.txt");
        Properties props = new Properties();
        assertNotNull("test classpath not set correctly",is);
        DataHelper.loadProps(props, is, true);
        _names = new ArrayList<String>((Set<String>) (Set) props.keySet());  // TODO-Java6: s/keySet()/stringPropertyNames()/
        Collections.shuffle(_names);
        is.close();
        
        // then copy the hosts.txt file so that the naming service can load them
        hostsTxt = new File(routerDir, "hosts.txt");
        OutputStream os = new BufferedOutputStream(new FileOutputStream(hostsTxt));
        is = getClass().getResourceAsStream("/hosts.txt");
        byte [] b = new byte[8196];
        int read = 0;
        while ((read = is.read(b)) > 0 )
            os.write(b,0,read);
        os.flush(); os.close();
        _bns = new BlockfileNamingService(ctx);
    }

    public void tearDown() {
        _bns.shutdown();
        if (routerDir != null) {
            File f = new File(routerDir,"hostsdb.blockfile");
            f.delete();
        }
        if (hostsTxt != null)
            hostsTxt.delete();
    }

    public void testRepeatedLookup() throws Exception{
        int found = 0;
        int notfound = 0;
        int rfound = 0;
        int rnotfound = 0;
        for (String name : _names) {
             Destination dest = _bns.lookup(name);
             if (dest != null) {
                 found++;
                 String reverse = _bns.reverseLookup(dest);
                 if (reverse != null)
                     rfound++;
                 else
                     rnotfound++;
             } else {
                 notfound++;
             }
        }
        assertEquals(0, notfound);
        assertEquals(0, rnotfound);
    }
}
