package net.i2p.client.naming;

import junit.framework.TestCase;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;


public class DummyNamingServiceTest extends TestCase {
    private I2PAppContext _context;

    public void setUp() {
        _context = new I2PAppContext();
    }

    public void testLookup() throws Exception{
        // The good b64 and b32 are the destination of www.i2p2.i2p =)
        String goodB64 = "-KR6qyfPWXoN~F3UzzYSMIsaRy4quickbrownfoxXSzUQXQdi2Af1TV2UMH3PpPuNu-GwrqihwmLSkPFg4fv4yQQY3E10VeQVuI67dn5vlan3NGMsjqxoXTSHHt7C3nX3szXK90JSoO~tRMDl1xyqtKm94-RpIyNcLXofd0H6b02683CQIjb-7JiCpDD0zharm6SU54rhdisIUVXpi1xYgg2pKVpssL~KCp7RAGzpt2rSgz~RHFsecqGBeFwJdiko-6CYW~tcBcigM8ea57LK7JjCFVhOoYTqgk95AG04-hfehnmBtuAFHWklFyFh88x6mS9sbVPvi-am4La0G0jvUJw9a3wQ67jMr6KWQ~w~bFe~FDqoZqVXl8t88qHPIvXelvWw2Y8EMSF5PJhWw~AZfoWOA5VQVYvcmGzZIEKtFGE7bgQf3rFtJ2FAtig9XXBsoLisHbJgeVb29Ew5E7bkwxvEe9NYkIqvrKvUAt1i55we0Nkt6xlEdhBqg6xXOyIAAAA";
        String goodB32 = "rjxwbsw4zjhv4zsplma6jmf5nr24e4ymvvbycd3swgiinbvg7oga.b32.i2p";
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
        assertNull(ns.lookup("www.i2p2.i2p"));
    }
}
