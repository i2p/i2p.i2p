package net.i2p.router.web;

import java.io.IOException;
import org.mortbay.jetty.Server;
import org.mortbay.util.MultiException;

public class RouterConsoleRunner {
    private Server _server;
    private String _listenPort = "7657";
    private String _listenHost = "0.0.0.0";
    private String _webAppsDir = "./webapps/";
    
    public RouterConsoleRunner(String args[]) {
        if (args.length == 3) {
            _listenPort = args[0].trim();
            _listenHost = args[1].trim();
            _webAppsDir = args[2].trim();
        }
    }
    
    public static void main(String args[]) {
        RouterConsoleRunner runner = new RouterConsoleRunner(args);
        runner.startConsole();
    }
    
    public void startConsole() {
        _server = new Server();
        try {
            _server.addListener(_listenHost + ':' + _listenPort);
            _server.setRootWebApp("routerconsole");
            _server.addWebApplications(_webAppsDir);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        try {
            _server.start();
        } catch (MultiException me) {
            me.printStackTrace();
        }
    }
    
    public void stopConsole() {
        try {
            _server.stop();
        } catch (InterruptedException ie) {
            ie.printStackTrace();
        }
    }
    
}
