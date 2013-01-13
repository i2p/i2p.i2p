package net.i2p.stat;

import java.util.Properties;

import org.junit.Test;

import junit.framework.TestCase;


public class RateStatTest extends TestCase {
    
    @Test
    public void testNoRates() throws Exception {
        final long emptyArray[] = new long[0];
        try {
            new RateStat("test", "test RateStat getters etc", "tests", emptyArray);
            fail("created a rate stat with no periods");
        } catch (IllegalArgumentException expected){}
    }
    
    @Test
    public void testGettersEtc() throws Exception{
        final long periods[] = new long[]{10};
        RateStat rs = new RateStat("test", "test RateStat getters etc", "tests", periods);

        // Test basic getters
        assertEquals("test", rs.getName());
        assertEquals("tests", rs.getGroupName());
        assertEquals("test RateStat getters etc", rs.getDescription());

        // There should be no data, so other getters should return defaults
        assertEquals(0.0, rs.getLifetimeAverageValue());
        assertEquals(0, rs.getLifetimeEventCount());
        assertNull(rs.getRate(2000));
    }
    
    @SuppressWarnings("deprecation")
    @Test
    public void testAddingAndRemovingThrows() throws Exception {
        final long periods[] = new long[]{10};
        RateStat rs = new RateStat("test", "test RateStat getters etc", "tests", periods);

        try {
            rs.addRate(1000);
            fail("adding periods should not be supported");
        } catch (UnsupportedOperationException expected){}
        try {
            rs.removeRate(10);
            fail("removing periods should not be supported");
        } catch (UnsupportedOperationException expected){}
    }

    @Test
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
