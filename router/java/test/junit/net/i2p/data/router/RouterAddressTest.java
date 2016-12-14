package net.i2p.data.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */
 
import java.io.ByteArrayOutputStream;
import java.util.Properties;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.StructureTest;

/**
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
public class RouterAddressTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        RouterAddress addr = new RouterAddress();
        byte data[] = new byte[32];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        addr.setCost(42);
        //addr.setExpiration(new Date(1000*60*60*24)); // jan 2 1970
        Properties options = new Properties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        addr.setOptions(options); // setOptions(...) is deprecated
        addr.setTransportStyle("Blah"); // setTransportStyle(...) is also deprecated
        return addr; 
    }
    public DataStructure createStructureToRead() { return new RouterAddress(); }

    public void testSetNullOptions(){
        RouterAddress addr = new RouterAddress();
        boolean error = false;
        try{
            addr.setOptions(null); // setOptions(...) is deprecated
        }catch(NullPointerException dfe){
            error = true;
        }
        assertTrue(error);
    }

    public void testSetOptionsAgain(){
        RouterAddress addr = new RouterAddress();
        Properties options = new Properties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        addr.setOptions(options); // setOptions(...) is deprecated
        options.setProperty("portnum", "2345");
        boolean error = false;
        try{
            addr.setOptions(options); // setOptions(...) is deprecated
        }catch(IllegalStateException dfe){
            error = true;
        }
        assertTrue(error);
    }

    public void testBadWrite() throws Exception{
        RouterAddress addr = new RouterAddress();
        boolean error = false;
        try{
            addr.writeBytes(new ByteArrayOutputStream());
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
    }

    public void testNullEquals(){
        RouterAddress addr = new RouterAddress();
        byte data[] = new byte[32];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        addr.setCost(42);
        //addr.setExpiration(new Date(1000*60*60*24)); // jan 2 1970
        Properties options = new Properties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        addr.setOptions(options);  // setOptions(...) is deprecated
        addr.setTransportStyle("Blah"); // setTransportStyle(...) is deprecated
        assertFalse(addr.equals(null));
        assertFalse(addr.equals(""));
    }

    public void testToString(){
        RouterAddress addr = new RouterAddress();
        byte data[] = new byte[32];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        addr.setCost(42);
        //addr.setExpiration(new Date(1000*60*60*24)); // jan 2 1970
        Properties options = new Properties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        addr.setOptions(options);  // setOptions(...) is deprecated
        addr.setTransportStyle("Blah"); // setTransportStyle(...) is deprecated
        String ret = addr.toString();
        //assertEquals("[RouterAddress: \n\tTransportStyle: Blah\n\tCost: 42\n\tExpiration: Fri Jan 02 00:00:00 UTC 1970\n\tOptions: #: 2\n\t\t[hostname] = [localhost]\n\t\t[portnum] = [1234]]", ret);
        assertEquals("[RouterAddress: \n\tType: Blah\n\tCost: 42\n\tOptions (2):\n\t\t[hostname] = [localhost]\n\t\t[portnum] = [1234]]", ret);
    }
}
