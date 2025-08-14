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
import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.web.StatsGenerator;
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
        // Check if connection is from localhost only
        if (!isLocalConnection()) {
            _log.warn("Admin connection rejected from non-localhost address");
            try {
                _socket.close();
            } catch (IOException e) {}
            return;
        }
        
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
        
        // Require authentication for sensitive admin operations
        if (requiresAuthentication(command) && !isAuthenticated(command)) {
            _log.warn("Unauthorized admin command attempt: " + command);
            replyUnauthorized(out, "Authentication required for admin operations");
            return;
        }
        
        if (command.indexOf("favicon") >= 0) {
            reply(out, "this is not a website");
        } else if ( (command.indexOf("routerStats.html") >= 0) || (command.indexOf("oldstats.jsp") >= 0) ) {
            try {
                out.write(DataHelper.getASCII("HTTP/1.1 200 OK\nConnection: close\nCache-control: no-cache\nContent-type: text/html\n\n"));
                _generator.generateStatsPage(new OutputStreamWriter(out), true);
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
                out.write(DataHelper.getASCII("HTTP/1.1 200 OK\nConnection: close\nCache-control: no-cache\nContent-type: text/html\n\n"));
                // TODO Not technically the same as router().renderStatusHTML() was
                _context.routerAppManager().renderStatusHTML(new OutputStreamWriter(out));
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
            out.write(DataHelper.getASCII(reply.toString()));
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
            out.write(DataHelper.getASCII(reply.toString()));
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

    /**
     * Determine if a command requires authentication based on its sensitivity.
     * Sensitive operations that expose internal data or allow system control require auth.
     *
     * @param command the admin command to check
     * @return true if authentication is required
     * @since 2.0.0
     */
    private boolean requiresAuthentication(String command) {
        if (command == null) return true;
        
        // Always require authentication for sensitive operations
        if (command.indexOf("/shutdown") >= 0 ||
            command.indexOf("/profile/") >= 0 ||
            command.indexOf("routerStats.html") >= 0 ||
            command.indexOf("oldstats.jsp") >= 0) {
            return true;
        }
        
        // Basic status and favicon don't require authentication
        if (command.indexOf("favicon") >= 0 ||
            command.indexOf("routerConsole.html") >= 0) {
            return false;
        }
        
        // Default to requiring authentication for unknown commands
        return true;
    }
    
    /**
     * Validate authentication for admin commands.
     * Enhanced security check that validates against configured admin credentials.
     *
     * @param command the command with potential authentication credentials
     * @return true if properly authenticated
     * @since 2.0.0  
     */
    private boolean isAuthenticated(String command) {
        if (command == null) return false;
        
        // For shutdown commands, password is validated in shutdown() method
        if (command.indexOf("/shutdown") >= 0) {
            String password = _context.router().getConfigSetting(SHUTDOWN_PASSWORD_PROP);
            if (password == null)
                password = _context.getProperty(SHUTDOWN_PASSWORD_PROP);
            return password != null && command.indexOf(password) > 0;
        }
        
        // For other admin commands, check for admin authentication
        String adminAuth = _context.getProperty("router.admin.auth");
        String adminPassword = _context.getProperty("router.admin.password");
        
        if (adminPassword != null) {
            // Basic password authentication for admin commands
            return command.indexOf(adminPassword) > 0;
        }
        
        if (adminAuth != null) {
            // Enhanced authentication token validation
            return validateAdminAuth(command, adminAuth);
        }
        
        // If no admin authentication is configured, log warning and deny access
        _log.warn("No admin authentication configured - denying access to: " + command);
        return false;
    }
    
    /**
     * Validate enhanced admin authentication token.
     *
     * @param command the command containing auth token
     * @param expectedAuth the expected authentication token
     * @return true if authentication is valid
     * @since 2.0.0
     */
    private boolean validateAdminAuth(String command, String expectedAuth) {
        // Extract auth token from command (implementation depends on protocol)
        // This is a simplified validation - in production this would be more sophisticated
        return command.indexOf(expectedAuth) > 0;
    }
    
    /**
     * Send HTTP 401 Unauthorized response for failed authentication.
     *
     * @param out output stream to write response
     * @param message error message to include
     * @throws IOException if writing fails
     * @since 2.0.0
     */
    private void replyUnauthorized(OutputStream out, String message) throws IOException {
        StringBuilder reply = new StringBuilder(512);
        reply.append("HTTP/1.1 401 Unauthorized\n");
        reply.append("Connection: close\n");
        reply.append("Cache-control: no-cache\n");
        reply.append("WWW-Authenticate: Basic realm=\"I2P Admin\"\n");
        reply.append("Content-type: text/html\n\n");
        reply.append("<html><head><title>401 Unauthorized</title></head>");
        reply.append("<body><h1>401 Unauthorized</h1>");
        reply.append("<p>").append(message).append("</p>");
        reply.append("<p>Authentication is required to access admin functions.</p>");
        reply.append("</body></html>");
        
        try {
            out.write(DataHelper.getASCII(reply.toString()));
            out.close();
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing unauthorized response: " + message);
            throw ioe;
        }
    }

    
    private boolean isLocalConnection() {
        try {
            InetAddress remoteAddr = _socket.getInetAddress();
            return remoteAddr.isLoopbackAddress() || remoteAddr.isAnyLocalAddress();
        } catch (Exception e) {
            _log.warn("Could not determine remote address", e);
            return false;
        }
    }
}
