package net.i2p.router.startup;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import net.i2p.data.DataHelper;

/**
 * Ugly code to make sure the service wrapper is configured
 * properly
 */
public class VerifyWrapperConfig {
    private static final String NL = System.getProperty("line.separator");
    
    public static boolean verifyConfig() {
        boolean cpUpdated = VerifyClasspath.updateClasspath();
        boolean pingUpdated = updatePing();
        return cpUpdated; // dont force the pingUpdated to cause a restart
    }
    
    private static boolean updatePing() {
        Properties p = new Properties();
        File configFile = new File("wrapper.config");
        try {
            DataHelper.loadProps(p, configFile);
            if (p.containsKey("wrapper.ping.interval"))
                return false;
            
            FileWriter out = new FileWriter(configFile, true);
            out.write(NL + "# Adding ping timeout as required by the update" + NL);
            out.write("wrapper.ping.interval=600" + NL);
            out.write("wrapper.ping.timeout=605" + NL);
            out.close();
            return true;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        }
    }
}
