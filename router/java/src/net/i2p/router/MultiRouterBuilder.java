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
                buildConfig(dir, basePortNum, i == 0);
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
        buildStartupScriptNix(args);
    }
    private static void buildStartupScriptNix(String args[]) {
        StringBuilder buf = new StringBuilder(4096);
        buf.append("#!/bin/sh\n");
        buf.append("export CP=.; for LIB in lib/* ; do export CP=$CP:$LIB ; done\n"); 
        buf.append("nohup java -cp $CP ");
        buf.append(" -Xmx1024m");
        buf.append(" -Djava.library.path=.");
        buf.append(" -DloggerFilenameOverride=logs/log-sim-#.txt");
        buf.append(" -Dorg.mortbay.http.Version.paranoid=true");
        buf.append(" -Dorg.mortbay.util.FileResource.checkAliases=false");
        buf.append(" -verbose:gc");
        
        buf.append(" net.i2p.router.MultiRouter baseEnv.txt ");
        for (int i = 0; i < args.length; i += 2) 
            buf.append(args[i]).append("/routerEnv.txt ");
        buf.append(" > sim.out 2>&1 &\n");
        buf.append("echo $! > sim.pid\n");
        buf.append("echo \"After all of the routers have started up, you should cross seed them\"\n");
        buf.append("echo \"Simply copy */netDb/routerInfo-* to all of the various */netDb/ directories\"\n");
        try {
            FileOutputStream fos = new FileOutputStream("runNetSim.sh");
            fos.write(buf.toString().getBytes());
            fos.close();
        } catch (IOException ioe) { ioe.printStackTrace(); }
    }
    
    private static void buildConfig(String dir, int basePort, boolean isFirst) {
        File baseDir = new File(dir);
        baseDir.mkdirs();
        File cfgFile = new File(baseDir, "router.config");
        StringBuilder buf = new StringBuilder(8*1024);
        buf.append("router.profileDir=").append(baseDir.getPath()).append("/peerProfiles\n");
        buf.append("router.historyFilename=").append(baseDir.getPath()).append("/messageHistory.txt\n");
        buf.append("router.sessionKeys.location=").append(baseDir.getPath()).append("/sessionKeys.dat\n");
        buf.append("router.info.location=").append(baseDir.getPath()).append("/router.info\n");
        buf.append("router.keys.location=").append(baseDir.getPath()).append("/router.keys\n");
        buf.append("router.networkDatabase.dbDir=").append(baseDir.getPath()).append("/netDb\n");
        buf.append("router.tunnelPoolFile=").append(baseDir.getPath()).append("/tunnelPool.dat\n");
        buf.append("router.keyBackupDir=").append(baseDir.getPath()).append("/keyBackup\n");
        buf.append("router.clientConfigFile=").append(baseDir.getPath()).append("/clients.config\n");
        buf.append("i2np.tcp.port=").append(basePort).append('\n');
        buf.append("i2np.tcp.hostname=localhost\n");
        buf.append("i2np.tcp.allowLocal=true\n");
        buf.append("i2np.tcp.disable=true\n");
        buf.append("i2np.tcp.enable=false\n");
        buf.append("i2np.udp.host=127.0.0.1\n");
        buf.append("i2np.udp.port=").append(basePort).append('\n');
        buf.append("i2np.udp.internalPort=").append(basePort).append('\n');
        buf.append("i2cp.port=").append(basePort+1).append('\n');
        buf.append("stat.logFile=").append(baseDir.getPath()).append("/stats.log\n");
        buf.append("stat.logFilters=*\n");
        
        try {
            FileOutputStream fos = new FileOutputStream(cfgFile);
            fos.write(buf.toString().getBytes());
            fos.close();
            
            File envFile = new File(baseDir, "routerEnv.txt");
            fos = new FileOutputStream(envFile);
            fos.write(("loggerFilenameOverride="+baseDir+ "/logs/log-router-#.txt\n").getBytes());
            fos.write(("router.configLocation="+baseDir+"/router.config\n").getBytes());
            fos.write(("router.pingFile="+baseDir+"/router.ping\n").getBytes());
            //fos.write(("i2p.vmCommSystem=true\n").getBytes());
            //fos.write(("i2p.encryption=off\n").getBytes());
            fos.close();
            
            File f = new File(baseDir, "logs");
            f.mkdirs();
            
            if (isFirst) {
                fos = new FileOutputStream(baseDir.getPath() + "/clients.config");
                fos.write(("clientApp.0.args=" + (basePort-1) + " 127.0.0.1 ./webapps\n").getBytes());
                fos.write(("clientApp.0.main=net.i2p.router.web.RouterConsoleRunner\n").getBytes());
                fos.write(("clientApp.0.name=webconsole\n").getBytes());
                fos.write(("clientApp.0.onBoot=true\n").getBytes());
                fos.write(("clientApp.1.args=\n").getBytes());
                fos.write(("clientApp.1.main=net.i2p.router.Counter\n").getBytes());
                fos.write(("clientApp.1.name=counter\n").getBytes());
                fos.write(("clientApp.1.onBoot=true\n").getBytes());
                fos.write(("clientApp.2.args=i2ptunnel.config\n").getBytes());
                fos.write(("clientApp.2.main=net.i2p.i2ptunnel.TunnelControllerGroup\n").getBytes());
                fos.write(("clientApp.2.name=tunnels\n").getBytes());
                fos.write(("clientApp.2.delay=60\n").getBytes());
                fos.close();
            }
        
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
    
    private static void usage() {
        System.err.println("Usage: MultiRouterBuilder [routerDir routerPortStart]*");
    }
}
