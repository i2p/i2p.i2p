package org.klomp.snark.web;

import java.io.File;

import net.i2p.I2PAppContext;
import net.i2p.util.FileUtil;

import org.mortbay.jetty.Server;

public class RunStandalone {
    private Server _server;
    
    static {
        System.setProperty("org.mortbay.http.Version.paranoid", "true");
        System.setProperty("org.mortbay.xml.XmlParser.NotValidating", "true");
    }
    
    private RunStandalone(String args[]) {}
    
    public static void main(String args[]) {
        RunStandalone runner = new RunStandalone(args);
        runner.start();
    }
    
    public void start() {
        File workDir = new File(I2PAppContext.getGlobalContext().getTempDir(), "jetty-work");
        boolean workDirRemoved = FileUtil.rmdir(workDir, false);
        if (!workDirRemoved)
            System.err.println("ERROR: Unable to remove Jetty temporary work directory");
        boolean workDirCreated = workDir.mkdirs();
        if (!workDirCreated)
            System.err.println("ERROR: Unable to create Jetty temporary work directory");
        
        try {
            _server = new Server("jetty-i2psnark.xml");
            // just blow up NPE if we don't have a context
            (_server.getContexts()[0]).setTempDirectory(workDir);
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
