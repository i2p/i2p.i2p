package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.apps.systray.SysTray;
import net.i2p.util.FileUtil;

import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.WebApplicationContext;
import org.mortbay.http.DigestAuthenticator;
import org.mortbay.http.handler.SecurityHandler;
import org.mortbay.http.HashUserRealm;
import org.mortbay.http.HttpRequest;
import org.mortbay.http.SecurityConstraint;
import org.mortbay.util.MultiException;

public class RouterConsoleRunner {
    private Server _server;
    private String _listenPort = "7657";
    private String _listenHost = "127.0.0.1";
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
        File workDir = new File("work");
        boolean workDirRemoved = FileUtil.rmdir(workDir, false);
        if (!workDirRemoved)
            System.err.println("ERROR: Unable to remove Jetty temporary work directory");
        boolean workDirCreated = workDir.mkdirs();
        if (!workDirCreated)
            System.err.println("ERROR: Unable to create Jetty temporary work directory");
        
        _server = new Server();
        WebApplicationContext contexts[] = null;
        try {
            _server.addListener(_listenHost + ':' + _listenPort);
            _server.setRootWebApp("routerconsole");
            contexts = _server.addWebApplications(_webAppsDir);
            if (contexts != null) {
                for (int i = 0; i < contexts.length; i++) 
                    initialize(contexts[i]);
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        try {
            _server.start();
        } catch (MultiException me) {
            me.printStackTrace();
        }
        try {
            SysTray tray = SysTray.getInstance();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
    private void initialize(WebApplicationContext context) {
        String password = getPassword();
        if (password != null) {
            HashUserRealm realm = new HashUserRealm();
            realm.put("admin", password);
            realm.addUserToRole("admin", "routerAdmin");
            context.setRealm(realm);
            context.setAuthenticator(new DigestAuthenticator());
            context.addHandler(0, new SecurityHandler());
            SecurityConstraint constraint = new SecurityConstraint("admin", "routerAdmin");
            constraint.setAuthenticate(true);
            context.addSecurityConstraint("/", constraint);
        }
    }
    
    private String getPassword() {
        List contexts = RouterContext.listContexts();
        if (contexts != null) {
            for (int i = 0; i < contexts.size(); i++) {
                RouterContext ctx = (RouterContext)contexts.get(i);
                String password = ctx.getProperty("consolePassword");
                if (password != null) {
                    password = password.trim();
                    if (password.length() > 0) {
                        return password;
                    }
                }
            }
            // no password in any context
            return null;
        } else {
            // no contexts?!
            return null;
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
