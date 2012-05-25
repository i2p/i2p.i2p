package net.i2p.stat;

import java.util.Properties;

import junit.framework.TestCase;


public class RateTest extends TestCase {
    public void testRate() throws Exception{
        Rate rate = new Rate(1000);
        for (int i = 0; i < 50; i++) {
            Thread.sleep(20);
            rate.addData(i * 100, 20);
        }
        rate.coalesce();
        StringBuilder buf = new StringBuilder(1024);
        
        rate.store("rate.test", buf);
        byte data[] = buf.toString().getBytes();

        Properties props = new Properties();
        props.load(new java.io.ByteArrayInputStream(data));

        Rate r = new Rate(props, "rate.test", true);

        assertEquals(r, rate);
    }
}