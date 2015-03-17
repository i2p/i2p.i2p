/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * ConfigFile.java
 * 2004 The I2P Project
 * http://www.i2p.net
 * This code is public domain.
 */

package net.i2p.apps.systray;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Simple config file handler.
 * 
 * Warning - browser needs double quotes and double backslashes on Windows
 * e.g.
 * browser="C:\\Program Files\\Mozilla Firefox\\firefox.exe"
 *
 * @author hypercubus
 */
public class ConfigFile {

    // TODO Make write operations keep original line comments intact.

    private String     _configFile;
    private Properties _properties = new Properties();

    /**
     * Initializes the {@link ConfigFile} object.
     * 
     * @param  configFile The config file to use.
     * @return            <code>false</code> if the given config file cannot be
     *                    located or accessed, otherwise <code>true</code>.
     */
    public boolean init(String configFile) {
        _configFile = configFile;
        return readConfigFile();
    }

    public String getProperty(String key) {
        return _properties.getProperty(key);
    }

    public String getProperty(String key, String defaultValue) {
        return _properties.getProperty(key, defaultValue);
    }

    public void setProperty(String key, String value) {
        _properties.setProperty(key, value);
        writeConfigFile();
    }

    private boolean readConfigFile() {

        FileInputStream fileInputStream = null;
        boolean rv = true;
        try {
            fileInputStream = new FileInputStream(_configFile);
            _properties.load(fileInputStream);
        } catch (Exception e) {
            rv = false;
        } finally {
            if (fileInputStream != null) {
                try { fileInputStream.close(); } catch (IOException e) {}
            }
        }
        return rv;
    }

    private boolean writeConfigFile() {

        FileOutputStream fileOutputStream = null;
        boolean rv = true;
        try {
            fileOutputStream = new FileOutputStream(_configFile);
            _properties.store(fileOutputStream, null);
        } catch (Exception e) {
            rv = false;
        } finally {
            if (fileOutputStream != null) {
                try { fileOutputStream.close(); } catch (IOException e) {}
            }
        }
        return rv;
    }
}
