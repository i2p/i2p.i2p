package net.i2p.i2ptunnel.access;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import org.junit.Test;
import static org.junit.Assert.*;

public class DefinitionParserTest {
    private static final String B32 = new Hash(new byte[32]).toBase32();

    @Test
    public void repeatedSpacesAndTabsAreAccepted() throws Exception {
        FilterDefinition definition = DefinitionParser.parse(new String[] { "  15/5  \t default  " });
        assertEquals(15, definition.getDefaultThreshold().getConnections());
        assertEquals(5, definition.getDefaultThreshold().getSeconds());
    }

    @Test
    public void keywordsAreCaseInsensitiveRegardlessOfLocale() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            FilterDefinition definition = DefinitionParser.parse(new String[] { "DENY DEFAULT", "ALLOW EXPLICIT " + B32 });
            assertSame(Threshold.DENY, definition.getDefaultThreshold());
            assertSame(Threshold.ALLOW, definition.getElements()[0].getThreshold());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void pathsWithSpacesArePreserved() throws Exception {
        FilterDefinition definition = DefinitionParser.parse(new String[] { "3/5   record   /tmp/access lists/abuse file.txt" });
        assertEquals(new File("/tmp/access lists/abuse file.txt"), definition.getRecorders()[0].getFile());
    }

    @Test
    public void blankLinesAndCommentsDoNotChangeDefault() throws Exception {
        FilterDefinition definition = DefinitionParser.parse(new String[] { "", "   # comment", "\t" });
        assertSame(Threshold.ALLOW, definition.getDefaultThreshold());
    }

    @Test
    public void missingRuleProducesCheckedLineError() throws Exception {
        invalid(new String[] { "# comment", "", "deny" }, 3);
    }

    @Test
    public void missingOperandsProduceCheckedErrors() throws Exception {
        for (String rule : new String[] { "explicit", "file", "record" })
            invalid(new String[] { "allow " + rule }, 1);
    }

    @Test
    public void surplusDefaultArgumentIsRejected() throws Exception {
        invalid(new String[] { "deny default ignored" }, 1);
    }

    @Test
    public void duplicateDefaultIdentifiesSecondLine() throws Exception {
        invalid(new String[] { "allow default", "deny default" }, 2);
    }

    @Test
    public void invalidThresholdsHaveLineNumbers() throws Exception {
        for (String threshold : new String[] { "1/0", "-1/2", "1/-2", "1/2/3", "oops", "2147483648/1" })
            invalid(new String[] { threshold + " default" }, 1);
    }

    @Test
    public void badDestinationAndUnknownRuleHaveLineNumbers() throws Exception {
        invalid(new String[] { "deny explicit invalid.b32.i2p" }, 1);
        invalid(new String[] { "deny unknown" }, 1);
    }

    @Test
    public void firstExplicitRuleRetainsPrecedence() throws Exception {
        FilterDefinition definition = DefinitionParser.parse(new String[] { "deny explicit " + B32, "allow explicit " + B32 });
        Map<Hash, DestTracker> trackers = new HashMap<Hash, DestTracker>();
        for (FilterDefinitionElement element : definition.getElements())
            element.update(trackers);
        assertTrue(trackers.get(new Hash(new byte[32])).recordAccess(10000));
    }

    @Test
    public void factoryReportsPhysicalLineIncludingComments() throws Exception {
        File file = File.createTempFile("access-definition-lines", ".rules");
        try {
            Files.write(file.toPath(), "# header\n\n# comment\ndeny explicit\n".getBytes("UTF-8"));
            try {
                FilterFactory.createFilter(I2PAppContext.getGlobalContext(), file);
                fail("Missing destination was accepted");
            } catch (InvalidDefinitionException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith("Line 4:"));
            }
        } finally {
            file.delete();
        }
    }

    private static void invalid(String[] lines, int line) throws Exception {
        try {
            DefinitionParser.parse(lines);
            fail("Invalid rule was accepted");
        } catch (InvalidDefinitionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("Line " + line + ":"));
        }
    }
}
