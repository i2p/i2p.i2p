package net.i2p.data.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */
 
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.util.Properties;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.StructureTest;
import net.i2p.util.OrderedProperties;

/**
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
public class RouterAddressTest extends StructureTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();

    public DataStructure createDataStructure() throws DataFormatException {
        //addr.setExpiration(new Date(1000*60*60*24)); // jan 2 1970
        OrderedProperties options = new OrderedProperties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        RouterAddress addr = new RouterAddress("Blah", options, 42);
        return addr; 
    }
    public DataStructure createStructureToRead() { return new RouterAddress(); }

    @SuppressWarnings("deprecation")
    @Test
    public void testSetNullOptions(){
        RouterAddress addr = new RouterAddress();

        exception.expect(NullPointerException.class);
        addr.setOptions(null);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testSetOptionsAgain(){
        OrderedProperties options = new OrderedProperties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        RouterAddress addr = new RouterAddress("Blah", options, 42);
        options.setProperty("portnum", "2345");

        exception.expect(IllegalStateException.class);
        addr.setOptions(options);
    }

    @Test
    public void testBadWrite() throws Exception{
        RouterAddress addr = new RouterAddress();

        exception.expect(DataFormatException.class);
        exception.expectMessage("uninitialized");
        addr.writeBytes(new ByteArrayOutputStream());
    }

    @Test
    public void testNullEquals(){
        //addr.setExpiration(new Date(1000*60*60*24)); // jan 2 1970
        OrderedProperties options = new OrderedProperties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        RouterAddress addr = new RouterAddress("Blah", options, 42);
        assertFalse(addr.equals(null));
        assertFalse(addr.equals(""));
    }

    @Test
    public void testToString(){
        //addr.setExpiration(new Date(1000*60*60*24)); // jan 2 1970
        OrderedProperties options = new OrderedProperties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        RouterAddress addr = new RouterAddress("Blah", options, 42);
        String ret = addr.toString();
        //assertEquals("[RouterAddress: \n\tTransportStyle: Blah\n\tCost: 42\n\tExpiration: Fri Jan 02 00:00:00 UTC 1970\n\tOptions: #: 2\n\t\t[hostname] = [localhost]\n\t\t[portnum] = [1234]]", ret);
        assertEquals("[RouterAddress: \n\tType: Blah\n\tCost: 42\n\tOptions (2):\n\t\t[hostname] = [localhost]\n\t\t[portnum] = [1234]]", ret);
    }
}
