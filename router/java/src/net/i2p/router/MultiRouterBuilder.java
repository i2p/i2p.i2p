package net.i2p.router;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Build a set of config files suitable for use by the multirouter as a 
 * simulation, as well as a pair of scripts for running the simulation.  
 * Usage: <pre>
 *   MultiRouterBuilder [routerDir routerPortStart]*
 * </pre>
 * 
 * Each router is configured with their own $routerDir/router.config file so
 * that all of its data is stored under the $routerDir (profiles, keys, netDb,
 * etc).  In addition, each router has the i2cp port set to $routerPortStart+1,
 * the admin port set to $routerPortStart+2, and some commented out clientApp
 * lines (configured with the SAM bridge at $routerPortStart+3 and an EepProxy at
 * $routerPortStart+4).<p />
 *
 * It then builds a $routerDir/heartbeat.config containing the following lines:<ul>
 *  <li>i2cpHost=localhost</li>
 *  <li>i2cpPort=$routerPortStart+1</li>
 *  <li>numHops=2</li>
 *  <li>privateDestinationFile=$routerDir/heartbeat.keys</li>
 *  <li>publicDestinationFile=$routerDir/heartbeat.txt</li>
 * </ul>
 *
 * Then it goes on to create the $routerDir/routerEnv.txt:<ul>
 *  <li>loggerFilenameOverride=$routerDir/logs/log-router-#.txt</li>
 *  <li>router.configLocation=$routerDir/router.config</li>
 *  <li>i2p.vmCommSystem=true</li>
 *  <li>i2p.encryption=off</li>
 * </ul>
 *
 * In addition, it creates a baseEnv.txt: <ul>
 *  <li>loggerFilenameOverride=logs/log-base-#.txt</li>
 * </ul>
 *
 * Finally, it creates the MultiRouter startup script to launch all of these
 * routers, stored at runNetSim.bat / runNetSim.sh
 *
 */
public class MultiRouterBuilder {
    public static void main(String args[]) {
        if (args.length < 2) {
            usage();
            return;
        }
        for (int i = 0; i < args.length; i += 2) {
            String dir = args[i];
            try {
                int basePortNum = Integer.parseInt(args[i+1]);
                buildConfig(dir, basePortNum);
            } catch (NumberFormatException nfe) {
                nfe.printStackTrace();
            }
        }
        buildBaseEnv();
        buildStartupScript(args);
    }
    
    private static void buildBaseEnv() {
        File envFile = new File("baseEnv.txt");
        try {
            FileOutputStream fos = new FileOutputStream(envFile);
            fos.write(("loggerFilenameOverride=logs/log-base-#.txt\n").getBytes());
            fos.close();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        File f = new File("logs");
        f.mkdirs();
    }
    
    private static void buildStartupScript(String args[]) {
        buildStartupScriptWin(args);
        buildStartupScriptNix(args);
    }
    private static void buildStartupScriptWin(String args[]) {
        StringBuffer buf = new StringBuffer(4096);
        buf.append("@echo off\ntitle I2P Router Sim\n");
        buf.append("echo After all of the routers have started up, you should cross seed them\n");
        buf.append("echo Simply copy */netDb/routerInfo-* to all of the various */netDb/ directories\n");
        buf.append("java -cp lib\\i2p.jar;lib\\router.jar;lib\\mstreaming.jar;");
        buf.append("lib\\heartbeat.jar;lib\\i2ptunnel.jar;lib\\netmonitor.jar;");
        buf.append("lib\\sam.jar ");
        buf.append("-Djava.library.path=. ");
        buf.append("-DloggerFilenameOverride=logs\\log-sim-#.txt ");
        buf.append("net.i2p.router.MultiRouter baseEnv.txt ");
        for (int i = 0; i < args.length; i += 2) 
            buf.append(args[i]).append("\\routerEnv.txt ");
        buf.append("\npause\n");
        try {
            FileOutputStream fos = new FileOutputStream("runNetSim.bat");
            fos.write(buf.toString().getBytes());
            fos.close();
        } catch (IOException ioe) { ioe.printStackTrace(); }
    }
    
    private static void buildStartupScriptNix(String args[]) {
        StringBuffer buf = new StringBuffer(4096);
        buf.append("#!/bin/sh\n");
        buf.append("nohup java -cp lib/i2p.jar:lib/router.jar:lib/mstreaming.jar:");
        buf.append("lib/heartbeat.jar:lib/i2ptunnel.jar:lib/netmonitor.jar:");
        buf.append("lib/sam.jar ");
        buf.append("-Djava.library.path=. ");
        buf.append("-DloggerFilenameOverride=logs/log-sim-#.txt ");
        buf.append("net.i2p.router.MultiRouter baseEnv.txt ");
        for (int i = 1; i < args.length; i += 2) 
            buf.append(args[i]).append("/routerEnv.txt ");
        buf.append(" > sim.txt &\n");
        buf.append("echo $! > sim.pid\n");
        buf.append("echo \"After all of the routers have started up, you should cross seed them\"\n");
        buf.append("echo \"Simply copy */netDb/routerInfo-* to all of the various */netDb/ directories\"\n");
        try {
            FileOutputStream fos = new FileOutputStream("runNetSim.sh");
            fos.write(buf.toString().getBytes());
            fos.close();
        } catch (IOException ioe) { ioe.printStackTrace(); }
    }
    
    private static void buildConfig(String dir, int basePort) {
        File baseDir = new File(dir);
        baseDir.mkdirs();
        File cfgFile = new File(baseDir, "router.config");
        StringBuffer buf = new StringBuffer(8*1024);
        buf.append("i2np.bandwidth.inboundBytesPerMinute=-60\n");
        buf.append("i2np.bandwidth.outboundBytesPerMinute=-60\n");
        buf.append("router.publishPeerRankings=true\n");
        buf.append("router.keepHistory=true\n");
        buf.append("router.submitHistory=false\n");
        buf.append("router.maxJobRunners=1\n");
        buf.append("router.jobLagWarning=10000\n");
        buf.append("router.jobLagFatal=30000\n");
        buf.append("router.jobRunWarning=10000\n");
        buf.append("router.jobRunFatal=30000\n");
        buf.append("router.jobWarmupTime=600000\n");
        buf.append("router.targetClients=1\n");
        buf.append("tunnels.numInbound=6\n");
        buf.append("tunnels.numOutbound=6\n");
        buf.append("tunnels.depthInbound=2\n");
        buf.append("tunnels.depthOutbound=2\n");
        buf.append("tunnels.tunnelDuration=600000\n");
        buf.append("router.maxWaitingJobs=30\n");
        buf.append("router.profileDir=").append(baseDir.getPath()).append("/peerProfiles\n");
        buf.append("router.historyFilename=").append(baseDir.getPath()).append("/messageHistory.txt\n");
        buf.append("router.sessionKeys.location=").append(baseDir.getPath()).append("/sessionKeys.dat\n");
        buf.append("router.info.location=").append(baseDir.getPath()).append("/router.info\n");
        buf.append("router.keys.location=").append(baseDir.getPath()).append("/router.keys\n");
        buf.append("router.networkDatabase.dbDir=").append(baseDir.getPath()).append("/netDb\n");
        buf.append("router.tunnelPoolFile=").append(baseDir.getPath()).append("/tunnelPool.dat\n");
        buf.append("router.keyBackupDir=").append(baseDir.getPath()).append("/keyBackup\n");
        buf.append("i2np.tcp.port=").append(basePort).append('\n');
        buf.append("i2cp.port=").append(basePort+1).append('\n');
        buf.append("router.adminPort=").append(basePort+2).append('\n');
        buf.append("#clientApp.0.main=net.i2p.sam.SAMBridge\n");
        buf.append("#clientApp.0.name=SAM\n");
        buf.append("#clientApp.0.args=localhost ").append(basePort+3).append(" i2cp.tcp.host=localhost i2cp.tcp.port=").append(basePort+1).append("\n");
        buf.append("#clientApp.1.main=net.i2p.i2ptunnel.I2PTunnel\n");
        buf.append("#clientApp.1.name=EepProxy\n");
        buf.append("#clientApp.1.args=-nogui -e \"config localhost ").append(basePort+1).append("\" -e \"httpclient ").append(basePort+4).append("\"\n");
        buf.append("#clientApp.2.main=net.i2p.heartbeat.Heartbeat\n");
        buf.append("#clientApp.2.name=Heartbeat\n");
        buf.append("#clientApp.2.args=").append(baseDir.getPath()).append("/heartbeat.config\n");
        
        try {
            FileOutputStream fos = new FileOutputStream(cfgFile);
            fos.write(buf.toString().getBytes());
            fos.close();
            
            fos = new FileOutputStream(new File(baseDir, "heartbeat.config"));
            StringBuffer tbuf = new StringBuffer(1024);
            tbuf.append("i2cpHost=localhost\n");
            tbuf.append("i2cpPort=").append(basePort+1).append('\n');
            tbuf.append("numHops=2\n");
            tbuf.append("privateDestinationFile=").append(baseDir.getPath()).append("/heartbeat.keys\n");
            tbuf.append("publicDestinationFile=").append(baseDir.getPath()).append("/heartbeat.txt\n");
            fos.write(tbuf.toString().getBytes());
            fos.close();
            
            
            File envFile = new File(baseDir, "routerEnv.txt");
            fos = new FileOutputStream(envFile);
            fos.write(("loggerFilenameOverride="+baseDir+ "/logs/log-router-#.txt\n").getBytes());
            fos.write(("router.configLocation="+baseDir+"/router.config\n").getBytes());
            fos.write(("i2p.vmCommSystem=true\n").getBytes());
            fos.write(("i2p.encryption=off\n").getBytes());
            fos.close();
            
            File f = new File(baseDir, "logs");
            f.mkdirs();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
    
    private static void usage() {
        System.err.println("Usage: MultiRouterBuilder [routerDir routerPortStart]*");
    }
}
