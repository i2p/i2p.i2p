package net.i2p.router.admin;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

class AdminRunner implements Runnable {
    private Log _log;
    private RouterContext _context;
    private Socket _socket;
    private StatsGenerator _generator;
    
    public AdminRunner(RouterContext context, Socket socket) {
        _context = context;
        _log = context.logManager().getLog(AdminRunner.class);
        _socket = socket;
        _generator = new StatsGenerator(context);
    }
    
    public void run() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(_socket.getInputStream()));
            OutputStream out = _socket.getOutputStream();
            
            String command = in.readLine();
            runCommand(command, out);
        } catch (IOException ioe) {
            _log.error("Error running admin command", ioe);
        }
    }
    
    private void runCommand(String command, OutputStream out) throws IOException {
        _log.debug("Command [" + command + "]");
        if (command.indexOf("favicon") >= 0) {
            reply(out, "this is not a website");
        } else if ( (command.indexOf("routerStats.html") >= 0) || (command.indexOf("oldstats.jsp") >= 0) ) {
            try {
                out.write("HTTP/1.1 200 OK\nConnection: close\nCache-control: no-cache\nContent-type: text/html\n\n".getBytes());
                _generator.generateStatsPage(new OutputStreamWriter(out));
                out.close();
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error writing out the admin reply");
                throw ioe;
            }
        } else if (command.indexOf("/profile/") >= 0) {
            replyText(out, getProfile(command));
        } else if (command.indexOf("/shutdown") >= 0) {
            reply(out, shutdown(command));
        } else if (true || command.indexOf("routerConsole.html") > 0) {
            try {
                out.write("HTTP/1.1 200 OK\nConnection: close\nCache-control: no-cache\nContent-type: text/html\n\n".getBytes());
                _context.router().renderStatusHTML(new OutputStreamWriter(out));
                out.close();
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error writing out the admin reply");
                throw ioe;
            }
        }
    }
    
    private void reply(OutputStream out, String content) throws IOException {
        StringBuilder reply = new StringBuilder(10240);
        reply.append("HTTP/1.1 200 OK\n");
        reply.append("Connection: close\n");
        reply.append("Cache-control: no-cache\n");
        reply.append("Content-type: text/html\n\n");
        reply.append(content);
        try {
            out.write(reply.toString().getBytes());
            out.close();
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing out the admin reply:\n" + content);
            throw ioe;
        }
    }
    
    private void replyText(OutputStream out, String content) throws IOException {
        StringBuilder reply = new StringBuilder(10240);
        reply.append("HTTP/1.1 200 OK\n");
        reply.append("Connection: close\n");
        reply.append("Cache-control: no-cache\n");
        reply.append("Content-type: text/plain\n\n");
        reply.append(content);
        try {
            out.write(reply.toString().getBytes());
            out.close();
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing out the admin reply:\n" + content);
            throw ioe;
        }
    }
    
    private String getProfile(String cmd) {
        Set peers = _context.profileOrganizer().selectAllPeers();
        for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            if (cmd.indexOf(peer.toBase64().substring(0,10)) >= 0) {
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(64*1024);
                    _context.profileOrganizer().exportProfile(peer, baos);
                    return new String(baos.toByteArray());
                } catch (IOException ioe) {
                    _log.error("Error exporting the profile", ioe);
                    return "Error exporting the peer profile\n";
                }
            }
        }
        
        return "No such peer is being profiled\n";
    }
    
    private static final String SHUTDOWN_PASSWORD_PROP = "router.shutdownPassword";
    private String shutdown(String cmd) {
        String password = _context.router().getConfigSetting(SHUTDOWN_PASSWORD_PROP);
        if (password == null)
            password = _context.getProperty(SHUTDOWN_PASSWORD_PROP);
        if (password == null) 
            return "No shutdown password specified in the config or context - <b>REFUSING SHUTDOWN</b>." +
                   "<a href=\"/routerConsole.html\">back</a>";
        if (cmd.indexOf(password) > 0) {
            I2PThread t = new I2PThread(new Runnable() {
                public void run() { 
                    try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
                    _context.router().shutdown(Router.EXIT_HARD);
                }
            });
            t.start();
            return "Shutdown request accepted.  Killing the router in 30 seconds";
        } else {
            return "Incorrect shutdown password specified.  Please edit your router.config appropriately." +
                   "<a href=\"/routerConsole.html\">back</a>";
        }
    }
}
