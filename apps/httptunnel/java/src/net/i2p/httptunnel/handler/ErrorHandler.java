package net.i2p.httptunnel.handler;

import java.io.IOException;
import java.io.OutputStream;

import net.i2p.httptunnel.HTTPListener;
import net.i2p.httptunnel.Request;
import net.i2p.util.Log;

/**
 * Handler for general error messages.
 */
public class ErrorHandler {

    private static final Log _log = new Log(ErrorHandler.class); /* UNUSED */

    /* package private */ErrorHandler() {

    }

    /**
     * @param req the Request
     * @param httpl an HTTPListener
     * @param out where to write the results
     * @param error the error that happened
     * @throws IOException
     */
    public void handle(Request req, HTTPListener httpl, OutputStream out, String error) throws IOException {
        // FIXME: Make nicer messages for more likely errors.
        out
           .write(("HTTP/1.1 500 Internal Server Error\r\n" + "Content-Type: text/html; charset=iso-8859-1\r\n\r\n")
                                                                                                                    .getBytes("ISO-8859-1"));
        out
           .write(("<html><head><title>" + error + "</title></head><body><h1>" + error
                   + "</h1>An internal error occurred while " + "handling a request by HTTPTunnel:<br><b>" + error + "</b><h2>Complete request:</h2><b>---</b><br><i><pre>\r\n")
                                                                                                                                                                                .getBytes("ISO-8859-1"));
        out.write(req.toByteArray());
        out.write(("</pre></i><br><b>---</b></body></html>").getBytes("ISO-8859-1"));
        out.flush();
    }
}