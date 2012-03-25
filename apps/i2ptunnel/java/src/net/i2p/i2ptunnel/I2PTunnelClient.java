/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

public class I2PTunnelClient extends I2PTunnelClientBase {

    /** list of Destination objects that we point at */
    protected List<Destination> dests;
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
        super(localPort, ownDest, l, notifyThis,
              "Standard client on " + tunnel.listenHost + ':' + localPort,
              tunnel, pkf);

        if (waitEventValue("openBaseClientResult").equals("error")) {
            notifyEvent("openClientResult", "error");
            return;
        }

        StringTokenizer tok = new StringTokenizer(destinations, ", ");
        dests = new ArrayList(1);
        while (tok.hasMoreTokens()) {
            String destination = tok.nextToken();
            Destination destN = _context.namingService().lookup(destination);
            if (destN == null)
                l.log("Could not resolve " + destination);
            else
                dests.add(destN);
        }

        if (dests.isEmpty()) {
            l.log("No valid target destinations found");
            notifyEvent("openClientResult", "error");
            // Nothing is listening for the above event, so it's useless
            // Maybe figure out where to put a waitEventValue("openClientResult") ??
            // In the meantime, let's do this the easy way
            // Note that b32 dests will often not be resolvable at instantiation time;
            // a delayed resolution system would be even better.

            // Don't close() here, because it does a removeSession() and then
            // TunnelController can't acquire() it to release() it.
            //close(true);
            // Unfortunately, super() built the whole tunnel before we get here.
            throw new IllegalArgumentException("No valid target destinations found");
            //return;
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
            if (_log.shouldLog(Log.INFO))
                _log.info("Error connecting", ex);
            //l.log("Error connecting: " + ex.getMessage());
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
            return dests.get(0);
        int index = _context.random().nextInt(size);
        return dests.get(index);
    }
}
