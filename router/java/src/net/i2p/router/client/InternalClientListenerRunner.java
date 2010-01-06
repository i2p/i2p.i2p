package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.net.Socket;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.InternalServerSocket;

/**
 * Listen for in-JVM connections on the internal "socket"
 *
 * @author zzz
 * @since 0.7.9
 */
public class InternalClientListenerRunner extends ClientListenerRunner {

    public InternalClientListenerRunner(RouterContext context, ClientManager manager, int port) {
        super(context, manager, port);
        _log = _context.logManager().getLog(InternalClientListenerRunner.class);
    }
    
    /** 
     * Start up the socket listener, listens for connections, and
     * fires those connections off via {@link #runConnection runConnection}.  
     * This only returns if the socket cannot be opened or there is a catastrophic
     * failure.
     *
     */
    public void runServer() {
        try {
            if (_log.shouldLog(Log.INFO))
                _log.info("Listening on internal port " + _port);
            _socket = new InternalServerSocket(_port);
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("InternalServerSocket created, before accept: " + _socket);
            
            _listening = true;
            _running = true;
            while (_running) {
                try {
                    Socket socket = _socket.accept();
                    if (validate(socket)) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Internal connection received");
                        runConnection(socket);
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Refused connection from " + socket.getInetAddress());
                        try {
                            socket.close();
                        } catch (IOException ioe) {}
                    }
                } catch (IOException ioe) {
                    if (_context.router().isAlive()) 
                        _log.error("Server error accepting", ioe);
                } catch (Throwable t) {
                    if (_context.router().isAlive()) 
                        _log.error("Fatal error running client listener - killing the thread!", t);
                    _listening = false;
                    return;
                }
            }
        } catch (IOException ioe) {
            if (_context.router().isAlive()) 
                _log.error("Error listening on internal port " + _port, ioe);
        }
        
        _listening = false;
        if (_socket != null) {
            try { _socket.close(); } catch (IOException ioe) {}
            _socket = null; 
        }
        

        if (_context.router().isAlive())
            _log.error("CANCELING I2CP LISTEN", new Exception("I2CP Listen cancelled!!!"));
        _running = false;
    }
}
