package net.i2p.desktopgui.persistence;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author mathias
 */
public class PropertyManager {
    
    public static void setProps(Properties props) {
        PropertyManager.props = props;
    }
    
    public static Properties getProps() {
        return props;
    }
    
    public static Properties loadProps() {
        Properties defaultProps = new Properties();
        defaultProps.setProperty("firstLoad", "true");

        // create application properties with default
        Properties applicationProps = new Properties(defaultProps);

        // now load properties from last invocation
        FileInputStream in;
        try {
            in = new FileInputStream(PROPSLOCATION);
            applicationProps.load(in);
            in.close();
        } catch (FileNotFoundException ex) {
            //Nothing serious, just means it's being loaded for the first time.
        } catch(IOException ex) {
            Logger.getLogger(PropertyManager.class.getName()).log(Level.INFO, null, ex);
        }
        props = applicationProps;
        return applicationProps;
    }
    
    public static void saveProps(Properties props) {
        FileOutputStream out;
        try {
            File d = new File(PROPSDIRECTORY);
            if(!d.exists())
                d.mkdir();
            File f = new File(PROPSLOCATION);
            if(!f.exists())
                f.createNewFile();
            out = new FileOutputStream(f);
            props.store(out, PROPSLOCATION);
        } catch (FileNotFoundException ex) {
            Logger.getLogger(PropertyManager.class.getName()).log(Level.SEVERE, null, ex);
        } catch(IOException ex) {
            Logger.getLogger(PropertyManager.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    private static Properties props;
    
    ///Location where we store the Application Properties
    public static final String PROPSDIRECTORY = "desktopgui";
    public static final String PROPSFILENAME = "appProperties";
    public static final String PROPSLOCATION = PROPSDIRECTORY + File.separator + PROPSFILENAME;
}
