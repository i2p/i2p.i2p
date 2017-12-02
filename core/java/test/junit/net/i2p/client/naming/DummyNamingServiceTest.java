package net.i2p.client.naming;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;


public class DummyNamingServiceTest {
    // The good b64 and b32 are the destination of www.i2p-projekt.i2p =)
    private static String goodB64 = "8ZAW~KzGFMUEj0pdchy6GQOOZbuzbqpWtiApEj8LHy2~O~58XKxRrA43cA23a9oDpNZDqWhRWEtehSnX5NoCwJcXWWdO1ksKEUim6cQLP-VpQyuZTIIqwSADwgoe6ikxZG0NGvy5FijgxF4EW9zg39nhUNKRejYNHhOBZKIX38qYyXoB8XCVJybKg89aMMPsCT884F0CLBKbHeYhpYGmhE4YW~aV21c5pebivvxeJPWuTBAOmYxAIgJE3fFU-fucQn9YyGUFa8F3t-0Vco-9qVNSEWfgrdXOdKT6orr3sfssiKo3ybRWdTpxycZ6wB4qHWgTSU5A-gOA3ACTCMZBsASN3W5cz6GRZCspQ0HNu~R~nJ8V06Mmw~iVYOu5lDvipmG6-dJky6XRxCedczxMM1GWFoieQ8Ysfuxq-j8keEtaYmyUQme6TcviCEvQsxyVirr~dTC-F8aZ~y2AlG5IJz5KD02nO6TRkI2fgjHhv9OZ9nskh-I2jxAzFP6Is1kyAAAA";
    private static String goodB32 = "udhdrtrcetjm5sxzskjyr5ztpeszydbh4dpl3pl4utgqqw2v4jna.b32.i2p";

    // TODO: Come up with an actual bad b64 and b32
    private static String badB64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static String badB32 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.b32.i2p";

    private I2PAppContext _context;

    @Before
    public void setUp() throws Exception {
        _context = new I2PAppContext();
    }

    @Test
    public void lookupReturnsNullOnEmptyString() throws Exception {
        DummyNamingService ns = new DummyNamingService(_context);

        assertNull(ns.lookup(""));
        // TODO: Could this case ever come up?
        //assertNull(ns.lookup(null));
    }

    @Test
    public void lookupGoodDest() throws Exception {
        DummyNamingService ns = new DummyNamingService(_context);

        Destination dGoodB64 = ns.lookup(goodB64);
        assertNotNull(dGoodB64);
        // TODO: Check that the b64 is preserved.

        // TODO: Mock out I2CP response
        //Destination dGoodB32 = ns.lookup(goodB32);
        //assertNotNull(dGoodB32);
        // TODO: Check that the b32 is preserved.

        // TODO: Come up with an actual bad b64 and b32
        //assertNull(ns.lookup(badB64));
        //assertNull(ns.lookup(badB32));
    }

    @Test
    public void lookupReturnsNullOnHostname() throws Exception {
        DummyNamingService ns = new DummyNamingService(_context);

        // DummyNameService only handles b64 and b32 addresses
        assertNull(ns.lookup("www.i2p-projekt.i2p"));
    }
}
