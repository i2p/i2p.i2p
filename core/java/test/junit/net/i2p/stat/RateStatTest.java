package net.i2p.stat;

import java.util.Properties;

import junit.framework.TestCase;


public class RateStatTest extends TestCase {
    public void testGettersEtc() throws Exception{
        long emptyArray[] = new long[0];
        RateStat rs = new RateStat("test", "test RateStat getters etc", "tests", emptyArray);

        // Test basic getters
        assertEquals("test", rs.getName());
        assertEquals("tests", rs.getGroupName());
        assertEquals("test RateStat getters etc", rs.getDescription());

        // There should be no periods, so other getters should return defaults
        // TODO: Fix this so it checks that the array is empty rather than comparing objects
        //assertEquals(rs.getPeriods(), emptyArray);
        assertEquals(0.0, rs.getLifetimeAverageValue());
        assertEquals(0, rs.getLifetimeEventCount());
        assertNull(rs.getRate(2000));

        // Test adding and removing a period
        assertFalse(rs.containsRate(1000));
        rs.addRate(1000);
        assertTrue(rs.containsRate(1000));
        rs.removeRate(1000);
        assertFalse(rs.containsRate(1000));
    }

    public void testRateStat() throws Exception{
        RateStat rs = new RateStat("moo", "moo moo moo", "cow trueisms", new long[] { 60 * 1000, 60 * 60 * 1000,
                                                                                     24 * 60 * 60 * 1000});
        for (int i = 0; i < 50; i++) {
            Thread.sleep(20);
            rs.addData(i * 100, 20);
        }
        rs.coalesceStats();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(2048);
        
        rs.store(baos, "rateStat.test");
        byte data[] = baos.toByteArray();

        Properties props = new Properties();
        props.load(new java.io.ByteArrayInputStream(data));

        RateStat loadedRs = new RateStat("moo", "moo moo moo", "cow trueisms", new long[] { 60 * 1000,
                                                                                           60 * 60 * 1000,
                                                                                           24 * 60 * 60 * 1000});
        loadedRs.load(props, "rateStat.test", true);

        assertEquals(rs, loadedRs);
        
    }
}
