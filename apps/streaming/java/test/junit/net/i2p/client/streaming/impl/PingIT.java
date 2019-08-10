package net.i2p.client.streaming.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;

import org.junit.Test;

import junit.framework.TestCase;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.IncomingConnectionFilter;

public class PingIT extends TestCase {

    @Test
    public void test() throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        I2PSession session = createSession();
        ConnectionManager mgr = new ConnectionManager(
            context, session, new ConnectionOptions(), IncomingConnectionFilter.ALLOW);
        for (int i = 0; i < 10; i++) {
            boolean ponged = mgr.ping(session.getMyDestination(), 2*1000);
            assertTrue(ponged);
        }
    }
    
    private I2PSession createSession() throws Exception {
        I2PClient client = I2PClientFactory.createClient();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        client.createDestination(baos);
        I2PSession sess = client.createSession(new ByteArrayInputStream(baos.toByteArray()), new Properties());
        sess.connect();
        return sess;
    }
}
