/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.net.Socket;

import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

public class I2PTunnelClient extends I2PTunnelClientBase {

    private static final Log _log = new Log(I2PTunnelClient.class);

    protected Destination dest;

    public I2PTunnelClient(int localPort, String destination, Logging l, boolean ownDest, EventDispatcher notifyThis) {
        super(localPort, ownDest, l, notifyThis, "SynSender");

        if (waitEventValue("openBaseClientResult").equals("error")) {
            notifyEvent("openClientResult", "error");
            return;
        }

        try {
            dest = I2PTunnel.destFromName(destination);
            if (dest == null) {
                l.log("Could not resolve " + destination + ".");
                return;
            }
        } catch (DataFormatException e) {
            l.log("Bad format in destination \"" + destination + "\".");
            notifyEvent("openClientResult", "error");
            return;
        }

        setName(getLocalPort() + " -> " + destination);

        startRunning();

        notifyEvent("openClientResult", "ok");
    }

    protected void clientConnectionRun(Socket s) {
        try {
            I2PSocket i2ps = createI2PSocket(dest);
            new I2PTunnelRunner(s, i2ps, sockLock, null);
        } catch (Exception ex) {
            _log.info("Error connecting", ex);
            l.log(ex.getMessage());
            // s has been initialized before the try block...
            closeSocket(s);
        }
    }
}
