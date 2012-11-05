package net.i2p.client.streaming;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 *
 */
public class PingTest {
    public void test() {
        try {
            I2PAppContext context = I2PAppContext.getGlobalContext();
            I2PSession session = createSession();
            ConnectionManager mgr = new ConnectionManager(context, session, -1, null);
            Log log = context.logManager().getLog(PingTest.class);
            for (int i = 0; i < 10; i++) {
                log.debug("ping " + i);
                long before = context.clock().now();
                boolean ponged = mgr.ping(session.getMyDestination(), 2*1000);
                long after = context.clock().now();
                log.debug("ponged? " + ponged + " after " + (after-before) + "ms");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try { Thread.sleep(30*1000); } catch (Exception e) {}
        
    }
    
    private I2PSession createSession() {
        try {
            I2PClient client = I2PClientFactory.createClient();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            Destination dest = client.createDestination(baos);
            I2PSession sess = client.createSession(new ByteArrayInputStream(baos.toByteArray()), new Properties());
            sess.connect();
            return sess;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("b0rk b0rk b0rk");
        }
    }
    
    public static void main(String args[]) {
        PingTest pt = new PingTest();
        pt.test();
    }
}
