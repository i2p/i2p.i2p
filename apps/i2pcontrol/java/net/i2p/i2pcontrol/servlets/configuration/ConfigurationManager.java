package net.i2p.i2pcontrol.servlets.configuration;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * Manage the configuration of I2PControl.
 * @author mathias
 * modified: hottuna
 *
 */
public class ConfigurationManager {
    private final String CONFIG_FILE = "I2PControl.conf";
    private final String WEBAPP_CONFIG_FILE = "i2pcontrol.config";
    private final File configLocation;
    private final Log _log;
    private boolean _changed;

    //Configurations with a String as value
    private final Map<String, String> stringConfigurations = new HashMap<String, String>();
    //Configurations with a Boolean as value
    private final Map<String, Boolean> booleanConfigurations = new HashMap<String, Boolean>();
    //Configurations with an Integer as value
    private final Map<String, Integer> integerConfigurations = new HashMap<String, Integer>();



    public ConfigurationManager(I2PAppContext ctx, File dir, boolean isPlugin) {
        _log = ctx.logManager().getLog(ConfigurationManager.class);
        if (isPlugin) {
            configLocation = new File(dir, CONFIG_FILE);
        } else {
            configLocation = new File(dir, WEBAPP_CONFIG_FILE);
        }
        readConfFile();
    }

    /** @since 0.12 */
    public File getConfFile() {
        return configLocation;
    }

    /**
     * Collects arguments of the form --word, --word=otherword and -blah
     * to determine user parameters.
     * @param settingNames Command line arguments to the application
     */
/****
    public void loadArguments(String[] settingNames) {
        for (int i = 0; i < settingNames.length; i++) {
            String settingName = settingNames[i];
            if (settingName.startsWith("--")) {
                parseConfigStr(settingName.substring(2));
            }
        }
    }
****/

    /**
     * Reads configuration from file, every line is parsed as key=value.
     */
    public synchronized void readConfFile() {
        try {
            Properties input = new Properties();
            // true: map to lower case
            DataHelper.loadProps(input, configLocation, true);
            parseConfigStr(input);
            _changed = false;
        } catch (FileNotFoundException e) {
            if (_log.shouldInfo())
                _log.info("Unable to find config file, " + configLocation);
        } catch (IOException e) {
            _log.error("Unable to read from config file, " + configLocation, e);
        }
    }

    /**
     * Write configuration into default config file.
     * As of 0.12, doesn't actually write unless something changed.
     */
    public synchronized void writeConfFile() {
        if (!_changed)
            return;
        Properties tree = new OrderedProperties();
        tree.putAll(stringConfigurations);
        for (Entry<String, Integer> e : integerConfigurations.entrySet()) {
            tree.put(e.getKey(), e.getValue().toString());
        }
        for (Entry<String, Boolean> e : booleanConfigurations.entrySet()) {
            tree.put(e.getKey(), e.getValue().toString());
        }
        try {
            DataHelper.storeProps(tree, configLocation);
            _changed = false;
        } catch (IOException e1) {
            _log.error("Couldn't open file, " + configLocation + " for writing config.");
        }
    }

    /**
     * Try to parse the input as 'key=value',
     * where value will (in order) be parsed as integer/boolean/string.
     * @param str
     */
    private void parseConfigStr(Properties input) {
        for (Entry<Object, Object> entry : input.entrySet()) {
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            //Try parse as integer.
            try {
                int i = Integer.parseInt(value);
                integerConfigurations.put(key, i);
                continue;
            } catch (NumberFormatException e) {}
            //Check if value is a bool
            if (value.toLowerCase().equals("true")) {
                booleanConfigurations.put(key, Boolean.TRUE);
                continue;
            } else if (value.toLowerCase().equals("false")) {
                booleanConfigurations.put(key, Boolean.FALSE);
                continue;
            }
            stringConfigurations.put(key, value);
        }
    }


    /**
     * Check if a specific boolean configuration exists.
     * @param settingName The key for the configuration.
     * @param defaultValue If the configuration is not found, we use a default value.
     * @return The value of a configuration: true if found, defaultValue if not found.
     */
    public synchronized boolean getConf(String settingName, boolean defaultValue) {
        Boolean value = booleanConfigurations.get(settingName);
        if (value != null) {
            return value;
        } else {
            booleanConfigurations.put(settingName, defaultValue);
            _changed = true;
            return defaultValue;
        }
    }


    /**
     * Check if a specific boolean configuration exists.
     * @param settingName The key for the configuration.
     * @param defaultValue If the configuration is not found, we use a default value.
     * @return The value of a configuration: true if found, defaultValue if not found.
     */
    public synchronized int getConf(String settingName, int defaultValue) {
        Integer value = integerConfigurations.get(settingName);
        if (value != null) {
            return value;
        } else {
            integerConfigurations.put(settingName, defaultValue);
            _changed = true;
            return defaultValue;
        }
    }

    /**
     * Get a specific String configuration.
     * @param settingName The key for the configuration.
     * @param defaultValue If the configuration is not found, we use a default value.
     * @return The value of the configuration, or the defaultValue.
     */
    public synchronized String getConf(String settingName, String defaultValue) {
        String value = stringConfigurations.get(settingName);
        if (value != null) {
            return value;
        } else {
            stringConfigurations.put(settingName, defaultValue);
            _changed = true;
            return defaultValue;
        }
    }

    /**
     * Set a specific int setting
     * @param settingName
     * @param nbr
     */
    public synchronized void setConf(String settingName, int nbr) {
        integerConfigurations.put(settingName, nbr);
        _changed = true;
    }

    /**
     * Set a specific string setting
     * @param settingName
     * @param str
     */
    public synchronized void setConf(String settingName, String str) {
        stringConfigurations.put(settingName, str);
        _changed = true;
    }

    /**
     * Set a specific boolean setting
     * @param settingName
     * @param bool
     */
    public synchronized void setConf(String settingName, boolean bool) {
        booleanConfigurations.put(settingName, bool);
        _changed = true;
    }
}
