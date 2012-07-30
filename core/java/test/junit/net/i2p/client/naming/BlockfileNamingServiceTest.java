package net.i2p.client.naming;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import junit.framework.TestCase;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;


public class BlockfileNamingServiceTest extends TestCase {
    BlockfileNamingService _bns;
    List<String> _names;

    public void setUp() {
        I2PAppContext ctx = new I2PAppContext();
        _bns = new BlockfileNamingService(ctx);
        _names = null;
        Properties props = new Properties();
        try {
            DataHelper.loadProps(props, new File("../../installer/resources/hosts.txt"), true);
            _names = new ArrayList(props.keySet());
            Collections.shuffle(_names);
        } catch (IOException ioe) {
            _bns.shutdown();
            return;
        }
    }

    public void tearDown() {
        _bns.shutdown();
        File f = new File("hostsdb.blockfile");
        f.delete();
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
