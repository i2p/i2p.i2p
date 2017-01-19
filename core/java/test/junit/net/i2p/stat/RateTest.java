package net.i2p.stat;

import java.io.ByteArrayInputStream;
import java.util.Properties;
import net.i2p.data.DataHelper;

import org.junit.Test;

import junit.framework.TestCase;


public class RateTest extends TestCase {
    
    @Test
    public void testRate() throws Exception{
        Rate rate = new Rate(5000);
        for (int i = 0; i < 50; i++) {
            Thread.sleep(20);
            rate.addData(i * 100, 20);
        }
        rate.coalesce();
        StringBuilder buf = new StringBuilder(1024);
        
        rate.store("rate.test", buf);
        byte data[] = DataHelper.getUTF8(buf.toString());

        Properties props = new Properties();
        props.load(new ByteArrayInputStream(data));

        Rate r = new Rate(props, "rate.test", true);

        assertEquals(r, rate);
    }
}
