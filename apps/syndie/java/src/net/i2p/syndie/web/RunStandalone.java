package net.i2p.syndie.web;

import java.io.File;

import net.i2p.util.FileUtil;
import org.mortbay.jetty.Server;

public class RunStandalone {
    private Server _server;
    
    static {
        System.setProperty("org.mortbay.http.Version.paranoid", "true");
        System.setProperty("syndie.rootDir", ".");
        System.setProperty("syndie.defaultSingleUserArchives", "http://syndiemedia.i2p.net:8000/archive/archive.txt");
        System.setProperty("syndie.defaultProxyHost", "");
        System.setProperty("syndie.defaultProxyPort", "");
    }
    
    private RunStandalone(String args[]) {}
    
    public static void main(String args[]) {
        RunStandalone runner = new RunStandalone(args);
        runner.start();
    }
    
    public void start() {
        File workDir = new File("work");
        boolean workDirRemoved = FileUtil.rmdir(workDir, false);
        if (!workDirRemoved)
            System.err.println("ERROR: Unable to remove Jetty temporary work directory");
        boolean workDirCreated = workDir.mkdirs();
        if (!workDirCreated)
            System.err.println("ERROR: Unable to create Jetty temporary work directory");
        
        try {
            _server = new Server("jetty-syndie.xml");
            _server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void stop() {
        try {
            _server.stop();
        } catch (InterruptedException ie) {
            ie.printStackTrace();
        }
    }
}
