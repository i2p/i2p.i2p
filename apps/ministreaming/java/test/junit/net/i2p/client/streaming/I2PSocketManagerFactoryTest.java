package net.i2p.client.streaming;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;

import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.streaming.impl.I2PSocketManagerFull;
import net.i2p.data.Destination;

import org.junit.Test;

public class I2PSocketManagerFactoryTest {
    @Test
    public void testCreateDiscMgr() throws Exception {
        I2PSocketManagerFull mgr = (I2PSocketManagerFull) I2PSocketManagerFactory.createDisconnectedManager(null, null, 0, null);
        assertThat(mgr, is(not(nullValue())));
        assertThat(mgr.getName(), is("manager"));

        assertTrue(mgr.getOpts().containsKey(I2PClient.PROP_RELIABILITY));
        assertThat(mgr.getOpts().getProperty(I2PClient.PROP_RELIABILITY), is(I2PClient.PROP_RELIABILITY_NONE));
    }

    @Test
    public void testCreateDiscMgr_customDest() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Destination dest = I2PClientFactory.createClient().createDestination(baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

        I2PSocketManagerFull mgr = (I2PSocketManagerFull) I2PSocketManagerFactory.createDisconnectedManager(bais, null, 0, null);
        assertThat(mgr, is(not(nullValue())));
        assertThat(mgr.getName(), is("manager"));

        assertThat(mgr.getSession().getMyDestination(), is(equalTo(dest)));
    }

    @Test
    public void testCreateDiscMgr_customI2CP() throws Exception {
        I2PSocketManagerFull mgr = (I2PSocketManagerFull) I2PSocketManagerFactory.createDisconnectedManager(null, "example.com", 3333, null);
        assertThat(mgr, is(not(nullValue())));
        assertThat(mgr.getName(), is("manager"));

        assertTrue(mgr.getOpts().containsKey(I2PClient.PROP_TCP_HOST));
        assertThat(mgr.getOpts().getProperty(I2PClient.PROP_TCP_HOST), is("example.com"));
        assertTrue(mgr.getOpts().containsKey(I2PClient.PROP_TCP_PORT));
        assertThat(mgr.getOpts().getProperty(I2PClient.PROP_TCP_PORT), is("3333"));
    }

    @Test
    public void testCreateDiscMgr_customOpts() throws Exception {
        Properties opts = new Properties();
        opts.setProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_BEST_EFFORT);
        opts.setProperty("foo", "bar");

        I2PSocketManagerFull mgr = (I2PSocketManagerFull) I2PSocketManagerFactory.createDisconnectedManager(null, null, 0, opts);
        assertThat(mgr, is(not(nullValue())));
        assertThat(mgr.getName(), is("manager"));

        assertTrue(mgr.getOpts().containsKey(I2PClient.PROP_RELIABILITY));
        assertThat(mgr.getOpts().getProperty(I2PClient.PROP_RELIABILITY), is(I2PClient.PROP_RELIABILITY_BEST_EFFORT));
        assertTrue(mgr.getOpts().containsKey("foo"));
        assertThat(mgr.getOpts().getProperty("foo"), is("bar"));
    }
}
