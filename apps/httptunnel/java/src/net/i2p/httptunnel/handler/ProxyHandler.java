package net.i2p.httptunnel.handler;

import java.io.IOException;
import java.io.OutputStream;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Destination;
import net.i2p.httptunnel.HTTPListener;
import net.i2p.httptunnel.Request;
import net.i2p.httptunnel.SocketManagerProducer;
import net.i2p.httptunnel.filter.Filter;
import net.i2p.httptunnel.filter.NullFilter;
import net.i2p.util.Log;

/**
 * Handler for proxying "normal" HTTP requests.
 */
public class ProxyHandler extends EepHandler {

    private static final Log _log = new Log(ErrorHandler.class); /* UNUSED */
    private static I2PAppContext _context = new I2PAppContext();

    /* package private */ProxyHandler(ErrorHandler eh) {
        super(eh);
    }

    /**
     * @param req a Request
     * @param httpl an HTTPListener
     * @param out where to write the results
     * @throws IOException
     */
    public void handle(Request req, HTTPListener httpl, OutputStream out
                       /*, boolean fromProxy */) throws IOException {
        SocketManagerProducer smp = httpl.getSMP();
        Destination dest = findProxy();
        if (dest == null) {
            errorHandler.handle(req, httpl, out, "Could not find proxy");
            return;
        }
        // one manager for all proxy requests
        I2PSocketManager sm = smp.getManager("--proxy--");
        Filter f = new NullFilter(); //FIXME: use other filter
        if (!handle(req, f, out, dest, sm)) {
            errorHandler.handle(req, httpl, out, "Unable to reach peer");
        }
    }

    private Destination findProxy() {
        //FIXME!
        return _context.namingService().lookup("squid.i2p");
    }
}