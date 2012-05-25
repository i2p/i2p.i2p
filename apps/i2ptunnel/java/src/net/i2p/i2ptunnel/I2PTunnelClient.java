/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

public class I2PTunnelClient extends I2PTunnelClientBase {

    private static final Log _log = new Log(I2PTunnelClient.class);

    /** list of Destination objects that we point at */
    protected List dests;
    private static final long DEFAULT_READ_TIMEOUT = 5*60*1000; // -1
    protected long readTimeout = DEFAULT_READ_TIMEOUT;

    /**
     * @param destinations comma delimited list of peers we target
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelClient(int localPort, String destinations, Logging l, 
                           boolean ownDest, EventDispatcher notifyThis, 
                           I2PTunnel tunnel, String pkf) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis, "SynSender", tunnel, pkf);

        if (waitEventValue("openBaseClientResult").equals("error")) {
            notifyEvent("openClientResult", "error");
            return;
        }

        StringTokenizer tok = new StringTokenizer(destinations, ", ");
        dests = new ArrayList(1);
        while (tok.hasMoreTokens()) {
            String destination = tok.nextToken();
            try {
                Destination destN = I2PTunnel.destFromName(destination);
                if (destN == null)
                    l.log("Could not resolve " + destination);
                else
                    dests.add(destN);
            } catch (DataFormatException dfe) {
                l.log("Bad format parsing \"" + destination + "\"");
            }
        }

        if (dests.size() <= 0) {
            l.log("No target destinations found");
            notifyEvent("openClientResult", "error");
            return;
        }

        setName(getLocalPort() + " -> " + destinations);

        startRunning();

        notifyEvent("openClientResult", "ok");
    }

    public void setReadTimeout(long ms) { readTimeout = ms; }
    public long getReadTimeout() { return readTimeout; }
    
    protected void clientConnectionRun(Socket s) {
        Destination destN = pickDestination();
        I2PSocket i2ps = null;
        try {
            i2ps = createI2PSocket(destN);
            i2ps.setReadTimeout(readTimeout);
            new I2PTunnelRunner(s, i2ps, sockLock, null, mySockets);
        } catch (Exception ex) {
            _log.info("Error connecting", ex);
            l.log(ex.getMessage());
            closeSocket(s);
            if (i2ps != null) {
                synchronized (sockLock) {
                    mySockets.remove(sockLock);
                }
            }
        }
    }
    
    private final Destination pickDestination() {
        int size = dests.size();
        if (size <= 0) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("No client targets?!");
            return null;
        }
        if (size == 1) // skip the rand in the most common case
            return (Destination)dests.get(0);
        int index = I2PAppContext.getGlobalContext().random().nextInt(size);
        return (Destination)dests.get(index);
    }
}
