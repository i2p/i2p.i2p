package net.i2p.stat;

import java.util.Properties;

import junit.framework.TestCase;


public class RateStatTest extends TestCase {
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