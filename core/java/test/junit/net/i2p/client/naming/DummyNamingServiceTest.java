package net.i2p.client.naming;


import junit.framework.TestCase;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;


public class DummyNamingServiceTest extends TestCase {
    private I2PAppContext _context;

    public void setUp() throws Exception {
        _context = new I2PAppContext();
    }

    public void testLookup() throws Exception{
        // The good b64 and b32 are the destination of www.i2p-projekt.i2p =)
        String goodB64 = "8ZAW~KzGFMUEj0pdchy6GQOOZbuzbqpWtiApEj8LHy2~O~58XKxRrA43cA23a9oDpNZDqWhRWEtehSnX5NoCwJcXWWdO1ksKEUim6cQLP-VpQyuZTIIqwSADwgoe6ikxZG0NGvy5FijgxF4EW9zg39nhUNKRejYNHhOBZKIX38qYyXoB8XCVJybKg89aMMPsCT884F0CLBKbHeYhpYGmhE4YW~aV21c5pebivvxeJPWuTBAOmYxAIgJE3fFU-fucQn9YyGUFa8F3t-0Vco-9qVNSEWfgrdXOdKT6orr3sfssiKo3ybRWdTpxycZ6wB4qHWgTSU5A-gOA3ACTCMZBsASN3W5cz6GRZCspQ0HNu~R~nJ8V06Mmw~iVYOu5lDvipmG6-dJky6XRxCedczxMM1GWFoieQ8Ysfuxq-j8keEtaYmyUQme6TcviCEvQsxyVirr~dTC-F8aZ~y2AlG5IJz5KD02nO6TRkI2fgjHhv9OZ9nskh-I2jxAzFP6Is1kyAAAA";
        String goodB32 = "udhdrtrcetjm5sxzskjyr5ztpeszydbh4dpl3pl4utgqqw2v4jna.b32.i2p";
        // TODO: Come up with an actual bad b64 and b32
        String badB64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String badB32 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.b32.i2p";

        DummyNamingService ns = new DummyNamingService(_context);

        assertNull(ns.lookup(""));
        // TODO: Could this case ever come up?
        //assertNull(ns.lookup(null));

        Destination dGoodB64 = ns.lookup(goodB64);
        assertNotNull(dGoodB64);
        // TODO: Check that the b64 is preserved.

        Destination dGoodB32 = ns.lookup(goodB32);
        assertNotNull(dGoodB32);
        // TODO: Check that the b32 is preserved.

        // TODO: Come up with an actual bad b64 and b32
        //assertNull(ns.lookup(badB64));
        //assertNull(ns.lookup(badB32));

        // DummyNameService only handles b64 and b32 addresses
        assertNull(ns.lookup("www.i2p-projekt.i2p"));
    }
}
