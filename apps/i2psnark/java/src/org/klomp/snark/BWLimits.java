/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */
package org.klomp.snark;

import java.util.Arrays;
import java.util.Properties;

import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSimpleClient;

/**
 * Connect via I2CP and ask the router the bandwidth limits.
 *
 * The call is blocking and returns null on failure.
 * Timeout is set to 5 seconds in I2PSimpleSession but it should be much faster.
 *
 * @author zzz
 */
class BWLimits {

    public static int[] getBWLimits(String host, int port) {
        int[] rv = null;
        try {
            I2PClient client = new I2PSimpleClient();
            Properties opts = new Properties();
            opts.put(I2PClient.PROP_TCP_HOST, host);
            opts.put(I2PClient.PROP_TCP_PORT, "" + port);
            I2PSession session = client.createSession(null, opts);
            session.connect();
            rv = session.bandwidthLimits();
            session.destroySession();
        } catch (I2PSessionException ise) {}
        return rv;
    }

    public static void main(String args[]) {
        System.out.println(Arrays.toString(getBWLimits("127.0.0.1", 7654)));
    }
}
