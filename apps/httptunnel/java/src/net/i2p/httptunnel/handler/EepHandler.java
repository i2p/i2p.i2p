package net.i2p.httptunnel.handler;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.I2PException;
import net.i2p.client.naming.NamingService;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Destination;
import net.i2p.httptunnel.HTTPListener;
import net.i2p.httptunnel.Request;
import net.i2p.httptunnel.SocketManagerProducer;
import net.i2p.httptunnel.filter.Filter;
import net.i2p.httptunnel.filter.NullFilter;
import net.i2p.util.Log;

/**
 * Handler for browsing Eepsites.
 */
public class EepHandler {

    private static final Log _log = new Log(EepHandler.class);

    protected ErrorHandler errorHandler;

    /* package private */EepHandler(ErrorHandler eh) {
        errorHandler = eh;
    }

    /**
     * @param req the Request
     * @param httpl an HTTPListener
     * @param out where to write the results
     * @param destination destination as a string, (subject to naming
     *  service lookup)
     * @throws IOException
     */
    public void handle(Request req, HTTPListener httpl, OutputStream out,
    /* boolean fromProxy, */String destination) throws IOException {
        SocketManagerProducer smp = httpl.getSMP();
        Destination dest = NamingService.getInstance().lookup(destination);
        if (dest == null) {
            errorHandler.handle(req, httpl, out, "Could not lookup host: " + destination);
            return;
        }
        I2PSocketManager sm = smp.getManager(destination);
        Filter f = new NullFilter(); //FIXME: use other filter
        req.setParam("Host: ", dest.toBase64());
        if (!handle(req, f, out, dest, sm)) {
            errorHandler.handle(req, httpl, out, "Unable to reach peer");
        }
    }

    /**
     * @param req the Request to send out
     * @param f a Filter to apply to the bytes retrieved from the Destination
     * @param out where to write the results
     * @param dest the Destination of the Request
     * @param sm an I2PSocketManager, to get a socket for the Destination
     * @return boolean, true if something was written, false otherwise.
     * @throws IOException
     */
    public boolean handle(Request req, Filter f, OutputStream out, Destination dest, I2PSocketManager sm)
                                                                                                         throws IOException {
        I2PSocket s = null;
        boolean written = false;
        try {
            synchronized (sm) {
                s = sm.connect(dest, new I2PSocketOptions());
            }
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream sout = new BufferedOutputStream(s.getOutputStream());
            sout.write(req.toByteArray());
            sout.flush();
            byte[] buffer = new byte[16384], filtered;
            int len;
            while ((len = in.read(buffer)) != -1) {
                if (len != buffer.length) {
                    byte[] b2 = new byte[len];
                    System.arraycopy(buffer, 0, b2, 0, len);
                    filtered = f.filter(b2);
                } else {
                    filtered = f.filter(buffer);
                }
                written = true;
                out.write(filtered);
            }
            filtered = f.finish();
            written = true;
            out.write(filtered);
            out.flush();
        } catch (IOException ex) {
            _log.error("Error while handling eepsite request");
            return written;
        } catch (I2PException ex) {
            _log.error("Error while handling eepsite request");
            return written;
        } finally {
            if (s != null) s.close();
        }
        return true;
    }
}