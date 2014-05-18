/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketAddress;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

public class I2PTunnelClient extends I2PTunnelClientBase {

    /**
     * list of Destination objects that we point at
     * @deprecated why protected? Is anybody using out-of-tree? Protected from the beginning (2004)
     */
    protected List<Destination> dests;

    /**
     * replacement for dests
     */
    private final List<I2PSocketAddress> _addrs;
    private static final long DEFAULT_READ_TIMEOUT = 5*60*1000; // -1
    protected long readTimeout = DEFAULT_READ_TIMEOUT;

    /**
     * @param destinations peers we target, comma- or space-separated. Since 0.9.9, each dest may be appended with :port
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelClient(int localPort, String destinations, Logging l, 
                           boolean ownDest, EventDispatcher notifyThis, 
                           I2PTunnel tunnel, String pkf) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis,
              "Standard client on " + tunnel.listenHost + ':' + localPort,
              tunnel, pkf);

        _addrs = new ArrayList<I2PSocketAddress>(1);
        if (waitEventValue("openBaseClientResult").equals("error")) {
            notifyEvent("openClientResult", "error");
            return;
        }

        dests = new ArrayList<Destination>(1);
        buildAddresses(destinations);

        if (_addrs.isEmpty()) {
            l.log("No valid target destinations found");
            notifyEvent("openClientResult", "error");
            // Nothing is listening for the above event, so it's useless
            // Maybe figure out where to put a waitEventValue("openClientResult") ??
            // In the meantime, let's do this the easy way

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

    /** @since 0.9.9 moved from constructor */
    private void buildAddresses(String destinations) {
        if (destinations == null)
            return;
        StringTokenizer tok = new StringTokenizer(destinations, ", ");
        synchronized(_addrs) {
            _addrs.clear();
            while (tok.hasMoreTokens()) {
                String destination = tok.nextToken();
                try {
                    // Try to resolve here but only log if it doesn't.
                    // Note that b32 _addrs will often not be resolvable at instantiation time.
                    // We will try again to resolve in clientConnectionRun()
                    I2PSocketAddress addr = new I2PSocketAddress(destination);
                    _addrs.add(addr);
                    if (addr.isUnresolved()) {
                        String name = addr.getHostName();
                        if (name.length() == 60 && name.endsWith(".b32.i2p"))
                            l.log("Warning - Could not resolve " + name +
                                  ", perhaps it is not up, will retry when connecting.");
                        else
                            l.log("Warning - Could not resolve " + name +
                                  ", you must add it to your address book for it to work.");
                    } else {
                        dests.add(addr.getAddress());
                    }
                } catch (IllegalArgumentException iae) {
                     l.log("Bad destination " + destination + " - " + iae);
                }
            }
        }
    }

    public void setReadTimeout(long ms) { readTimeout = ms; }
    public long getReadTimeout() { return readTimeout; }
    
    protected void clientConnectionRun(Socket s) {
        I2PSocket i2ps = null;
        try {
            I2PSocketAddress addr = pickDestination();
            if (addr == null)
                throw new UnknownHostException("No valid destination configured");
            Destination clientDest = addr.getAddress();
            if (clientDest == null)
                throw new UnknownHostException("Could not resolve " + addr.getHostName());
            int port = addr.getPort();
            i2ps = createI2PSocket(clientDest, port);
            i2ps.setReadTimeout(readTimeout);
            Thread t = new I2PTunnelRunner(s, i2ps, sockLock, null, null, mySockets,
                                (I2PTunnelRunner.FailCallback) null);
            t.start();
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
    
    private final I2PSocketAddress pickDestination() {
        synchronized(_addrs) {
            int size = _addrs.size();
            if (size <= 0) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("No client targets?!");
                return null;
            }
            if (size == 1) // skip the rand in the most common case
                return _addrs.get(0);
            int index = _context.random().nextInt(size);
            return _addrs.get(index);
        }
    }

    /**
     *  Update the dests then call super.
     *
     *  @since 0.9.9
     */
    @Override
    public void optionsUpdated(I2PTunnel tunnel) {
        if (getTunnel() != tunnel)
            return;
        Properties props = tunnel.getClientOptions();
        // see TunnelController.setSessionOptions()
        String targets = props.getProperty("targetDestination");
        buildAddresses(targets);
        super.optionsUpdated(tunnel);
    }
}
