package net.i2p.client.naming;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import junit.framework.TestCase;

import java.io.File;
import java.util.Collections;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;


public class SingleFileNamingServiceTest extends TestCase {
    private I2PAppContext _context;

    public void setUp() {
        _context = new I2PAppContext();
    }

    public void tearDown() {
        File f = new File("testhosts.txt");
        f.delete();
    }

    public void testAddRemoveLookup() throws Exception{
        String testB64 = "-KR6qyfPWXoN~F3UzzYSMIsaRy4quickbrownfoxXSzUQXQdi2Af1TV2UMH3PpPuNu-GwrqihwmLSkPFg4fv4yQQY3E10VeQVuI67dn5vlan3NGMsjqxoXTSHHt7C3nX3szXK90JSoO~tRMDl1xyqtKm94-RpIyNcLXofd0H6b02683CQIjb-7JiCpDD0zharm6SU54rhdisIUVXpi1xYgg2pKVpssL~KCp7RAGzpt2rSgz~RHFsecqGBeFwJdiko-6CYW~tcBcigM8ea57LK7JjCFVhOoYTqgk95AG04-hfehnmBtuAFHWklFyFh88x6mS9sbVPvi-am4La0G0jvUJw9a3wQ67jMr6KWQ~w~bFe~FDqoZqVXl8t88qHPIvXelvWw2Y8EMSF5PJhWw~AZfoWOA5VQVYvcmGzZIEKtFGE7bgQf3rFtJ2FAtig9XXBsoLisHbJgeVb29Ew5E7bkwxvEe9NYkIqvrKvUAt1i55we0Nkt6xlEdhBqg6xXOyIAAAA";
        Destination testDest = new Destination();
        testDest.fromBase64(testB64);
        String testB642 = "24SmhWiRDm-GzpV5Gq2sXhuvPpa1OihY7rkxQO4aHy5qKjr6zmEnZ3xQXdkFJJ0Z1lKy73XRmgCyys02G25Hl3cuxlZ2fNbp6KhOzlRKpOIAWFdSWZNF4Fp7sos0x-a-9fxOWnwwQ9MFcRYwixE~iCZf4JG~-Pd-MHgAuDhIX0P3~GmfUvo~9xPjof1ZsnaOV1zC0XUkHxZA5D6V0Bse~Ptfb66lPNcgBxIEntCStBAy~rTjaA3SdAufG29IRWDscpFq1-D4XPaXHnlXu7n7WdpFEM8WWd3ebUMqnq8XvLL1eqoWYzKCe3aaavC3W6~pJp8cxKl2IKrhvSFatHZ0chRg3B4~ja1Cxmw1psisplSkJqMnF921E6pury0i6GH52XAVoj4iiDY~EAvqDhzG-ThwlzTs~2JKzslwxOrD2ejd-dcKdi4i9xvi2JQ4Ib2Mw2ktaQhuAw3Y9EkqAs7oriQQN8N8dwIoYkJLfvh7ousm0iKJJvMt3s55PccM46SoAAAA";
        Destination testDest2 = new Destination();
        testDest2.fromBase64(testB642);

        SingleFileNamingService ns = new SingleFileNamingService(_context, "testhosts.txt");

        // testhosts.txt is empty.
        assertThat(ns.size(), is(equalTo(0)));
        assertThat(ns.getEntries(), is(equalTo(Collections.EMPTY_MAP)));
        assertThat(ns.lookup("test.i2p"), is(nullValue()));
        assertThat(ns.reverseLookup(testDest), is(nullValue()));

        // First put should add the hostname.
        ns.put("test.i2p", testDest);
        assertThat(ns.size(), is(equalTo(1)));
        assertThat(ns.getEntries().size(), is(equalTo(1)));
        assertThat(ns.lookup("test.i2p"), is(equalTo(testDest)));
        assertThat(ns.reverseLookup(testDest), is(equalTo("test.i2p")));

        // Second put should replace the first.
        ns.put("test.i2p", testDest2);
        assertThat(ns.size(), is(equalTo(1)));
        assertThat(ns.lookup("test.i2p"), is(equalTo(testDest2)));
        assertThat(ns.reverseLookup(testDest2), is(equalTo("test.i2p")));
        assertThat(ns.lookup("test.i2p"), is(not(equalTo(testDest))));
        assertThat(ns.reverseLookup(testDest), is(nullValue()));

        // Removing the hostname should give an empty file again.
        ns.remove("test.i2p");
        assertThat(ns.lookup("test.i2p"), is(nullValue()));
        assertThat(ns.reverseLookup(testDest2), is(nullValue()));
        // Odd quirk - the above lookups don't update size, but getEntries() does...
        assertThat(ns.size(), is(equalTo(1)));
        assertThat(ns.getEntries(), is(equalTo(Collections.EMPTY_MAP)));
        assertThat(ns.size(), is(equalTo(0)));
    }
}
