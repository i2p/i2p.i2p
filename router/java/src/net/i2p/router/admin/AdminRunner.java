package net.i2p.router.admin;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.Router;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

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
        } else if (command.indexOf("routerStats.html") >= 0) {
            reply(out, _generator.generateStatsPage());
        } else if (command.indexOf("/profile/") >= 0) {
            replyText(out, getProfile(command));
        } else if (true || command.indexOf("routerConsole.html") > 0) {
            reply(out, _context.router().renderStatusHTML());
        }
    }
    
    private void reply(OutputStream out, String content) throws IOException {
        StringBuffer reply = new StringBuffer(10240);
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
        StringBuffer reply = new StringBuffer(10240);
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
}
