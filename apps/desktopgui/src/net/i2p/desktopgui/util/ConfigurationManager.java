package net.i2p.desktopgui.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Manage the configuration of desktopgui.
 * @author mathias
 *
 */
public class ConfigurationManager {
	
	private static ConfigurationManager instance;
	///Configurations with a String as value
	private Map<String, String> stringConfigurations = new HashMap<String, String>();
	///Configurations with a Boolean as value
	private Map<String, Boolean> booleanConfigurations = new HashMap<String, Boolean>();

	private ConfigurationManager() {}
	
	public static ConfigurationManager getInstance() {
		if(instance == null) {
			instance = new ConfigurationManager();
		}
		return instance;
	}
	
	/**
	 * Collects arguments of the form --word, --word=otherword and -blah
	 * to determine user parameters.
	 * @param args Command line arguments to the application
	 */
	public void loadArguments(String[] args) {
		for(int i=0; i<args.length; i++) {
			String arg = args[i];
			if(arg.startsWith("--")) {
				arg = arg.substring(2);
				if(arg.length() < 1) {
					continue;
				}
				int equals = arg.indexOf('=');
				if(equals != -1 && equals < arg.length() - 1) { //String configuration
					loadStringConfiguration(arg, equals);
				}
				else { //Boolean configuration
					loadBooleanConfiguration(arg);
				}
			}
			else if(arg.startsWith("-")) { //Boolean configuration
				loadBooleanConfiguration(arg);
			}
		}
	}
	
	/**
	 * Add a boolean configuration.
	 * @param arg The key we wish to add as a configuration.
	 */
	public void loadBooleanConfiguration(String arg) {
		booleanConfigurations.put(arg, Boolean.TRUE);
	}
	
	/**
	 * Add a String configuration which consists a key and a value.
	 * @param arg String of the form substring1=substring2.
	 * @param equalsPosition Position of the '=' element.
	 */
	public void loadStringConfiguration(String arg, int equalsPosition) {
		String key = arg.substring(0, equalsPosition);
		String value = arg.substring(equalsPosition+1);
		stringConfigurations.put(key, value);
	}
	
	/**
	 * Check if a specific boolean configuration exists.
	 * @param arg The key for the configuration.
	 * @param defaultValue If the configuration is not found, we use a default value.
	 * @return The value of a configuration: true if found, defaultValue if not found.
	 */
	public boolean getBooleanConfiguration(String arg, boolean defaultValue) {
		Boolean value = ((Boolean) booleanConfigurations.get("startWithI2P"));
		System.out.println(value);
		if(value != null) {
			return value;
		}
		return defaultValue;
	}
	
	/**
	 * Get a specific String configuration.
	 * @param arg The key for the configuration.
	 * @param defaultValue If the configuration is not found, we use a default value.
	 * @return The value of the configuration, or the defaultValue.
	 */
	public String getStringConfiguration(String arg, String defaultValue) {
		String value = stringConfigurations.get(arg);
		System.out.println(value);
		if(value != null) {
			return value;
		}
		return defaultValue;
	}
}
