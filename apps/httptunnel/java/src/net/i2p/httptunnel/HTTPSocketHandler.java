package net.i2p.httptunnel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import net.i2p.httptunnel.handler.RootHandler;
import net.i2p.util.Log;

/**
 * Handles a single HTTP socket connection.
 */
public class HTTPSocketHandler extends Thread {

    private static final Log _log = new Log(HTTPSocketHandler.class);

    private Socket s;
    private HTTPListener httpl;
    private RootHandler h;

    /**
     * A public constructor.
     * @param httpl An HTTPListener, to listen for HTTP, no doubt
     * @param s A socket.
     */
    public HTTPSocketHandler(HTTPListener httpl, Socket s) {
        this.httpl = httpl;
        this.s = s;
        h = RootHandler.getInstance();
        start();
    }

    /* (non-Javadoc)
     * @see java.lang.Thread#run()
     */
    public void run() {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new BufferedInputStream(s.getInputStream());
            out = new BufferedOutputStream(s.getOutputStream());
            Request req = new Request(in);
            h.handle(req, httpl, out);
        } catch (IOException ex) {
            _log.error("Error while handling data", ex);
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) {
                    out.flush();
                    out.close();
                }
                s.close();
            } catch (IOException ex) {
                _log.error("IOException in finalizer", ex);
            }
        }
    }
}