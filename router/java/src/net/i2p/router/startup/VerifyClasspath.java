package net.i2p.router.startup;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Properties;

import net.i2p.data.DataHelper;

/**
 * Make sure that if there is a wrapper.config file, it includes
 * all of the jar files necessary for the current build.  
 * HOLY CRAP THIS IS UGLY.
 *
 */
public class VerifyClasspath {
    private static final String NL = System.getProperty("line.separator");
    private static final Set _jars = new HashSet();

    static {
        _jars.add("lib/ant.jar");
        _jars.add("lib/heartbeat.jar");
        _jars.add("lib/i2p.jar");
        _jars.add("lib/i2ptunnel.jar");
        _jars.add("lib/jasper-compiler.jar");
        _jars.add("lib/jasper-runtime.jar");
        _jars.add("lib/javax.servlet.jar");
        _jars.add("lib/jnet.jar");
        _jars.add("lib/mstreaming.jar");
        _jars.add("lib/netmonitor.jar");
        _jars.add("lib/org.mortbay.jetty.jar");
        _jars.add("lib/router.jar");
        _jars.add("lib/routerconsole.jar");
        _jars.add("lib/sam.jar");
        _jars.add("lib/wrapper.jar");
        _jars.add("lib/xercesImpl.jar");
        _jars.add("lib/xml-apis.jar");
        _jars.add("lib/jbigi.jar");
        _jars.add("lib/systray.jar");
        _jars.add("lib/systray4j.jar");
        _jars.add("lib/streaming.jar");
    }
    
    /** 
     * update the wrapper.config
     *
     * @return true if the classpath was updated and a restart is
     *              required, false otherwise.
     */
    public static boolean updateClasspath() {
        Properties p = new Properties();
        File configFile = new File("wrapper.config");
        Set needed = new HashSet(_jars);
        try {
            DataHelper.loadProps(p, configFile);
            Set toAdd = new HashSet();
            int entry = 1;
            while (true) {
                String value = p.getProperty("wrapper.java.classpath." + entry);
                if (value == null) break;
                needed.remove(value);
                entry++;
            }
            if (needed.size() <= 0) {
                // we have everything we need
                return false;
            } else {
                // add on some new lines
                FileWriter out = new FileWriter(configFile, true);
                out.write(NL + "# Adding new libs as required by the update" + NL);
                for (Iterator iter = needed.iterator(); iter.hasNext(); ) {
                    String name = (String)iter.next();
                    out.write("wrapper.java.classpath." + entry + "=" + name + NL);
                }
                out.close();
                return true;
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        }
    }
}
