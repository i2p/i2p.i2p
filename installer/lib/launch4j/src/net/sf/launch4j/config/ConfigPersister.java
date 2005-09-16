/*
 	launch4j :: Cross-platform Java application wrapper for creating Windows native executables
 	Copyright (C) 2005 Grzegorz Kowal

	This program is free software; you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation; either version 2 of the License, or
	(at your option) any later version.

	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with this program; if not, write to the Free Software
	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
*/

/*
 * Created on Apr 22, 2005
 */
package net.sf.launch4j.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Properties;

import net.sf.launch4j.Util;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class ConfigPersister {

	private static final ConfigPersister _instance = new ConfigPersister();

	private final XStream _xstream;
	private Config _config;
	private File _configPath;

	private ConfigPersister() {
		_xstream = new XStream(new DomDriver());
    	_xstream.alias("launch4jConfig", Config.class);
    	_xstream.alias("jre", Jre.class);
    	_xstream.alias("splash", Splash.class);
    	_xstream.alias("versionInfo", VersionInfo.class);
	}

	public static ConfigPersister getInstance() {
		return _instance;
	}
	
	public Config getConfig() {
		return _config;
	}

	public File getConfigPath() {
		return _configPath;
	}
	
	public File getOutputPath() throws IOException {
		if (_config.getOutfile().isAbsolute()) {
			return _config.getOutfile().getParentFile();
		}
		File parent = _config.getOutfile().getParentFile();
		return (parent != null) ? new File(_configPath, parent.getPath()) : _configPath;
	}
	
	public File getOutputFile() throws IOException {
		return _config.getOutfile().isAbsolute()
			? _config.getOutfile()
			: new File(getOutputPath(), _config.getOutfile().getName());
	}

	public void createBlank() {
		_config = new Config();
		_config.setJre(new Jre());
		_configPath = null;
	}

	public void setAntConfig(Config c, File basedir) {
		_config = c;
		_configPath = basedir;
	}

	public void load(File f) throws ConfigPersisterException {
		try {
			BufferedReader r = new BufferedReader(new FileReader(f));
	    	_config = (Config) _xstream.fromXML(r);
	    	r.close();
	    	setConfigPath(f);
		} catch (Exception e) {
			throw new ConfigPersisterException(e);
		}
	}

	/**
	 * Imports launch4j 1.x.x config file.
	 */
	public void loadVersion1(File f) throws ConfigPersisterException {
		try {
			Props props = new Props(f);
			_config = new Config();
			String header = props.getProperty(Config.HEADER);
			_config.setHeaderType(header == null
					|| header.toLowerCase().equals("guihead.bin") ? 0 : 1);
			_config.setJar(props.getFile(Config.JAR));
			_config.setOutfile(props.getFile(Config.OUTFILE));
			_config.setJre(new Jre());
			_config.getJre().setPath(props.getProperty(Jre.PATH));
			_config.getJre().setMinVersion(props.getProperty(Jre.MIN_VERSION));
			_config.getJre().setMaxVersion(props.getProperty(Jre.MAX_VERSION));
			_config.getJre().setArgs(props.getProperty(Jre.ARGS));
			_config.setJarArgs(props.getProperty(Config.JAR_ARGS));
			_config.setChdir("true".equals(props.getProperty(Config.CHDIR)) ? "." : null);
			_config.setCustomProcName("true".equals(
					props.getProperty("setProcName")));				// 1.x
			_config.setStayAlive("true".equals(props.getProperty(Config.STAY_ALIVE)));
			_config.setErrTitle(props.getProperty(Config.ERR_TITLE));
			_config.setIcon(props.getFile(Config.ICON));
			File splashFile = props.getFile(Splash.SPLASH_FILE);
			if (splashFile != null) {
				_config.setSplash(new Splash());
				_config.getSplash().setFile(splashFile);
				String waitfor = props.getProperty("waitfor");		// 1.x
				_config.getSplash().setWaitForWindow(waitfor != null && !waitfor.equals(""));
				String splashTimeout = props.getProperty(Splash.TIMEOUT);
				if (splashTimeout != null) {
					_config.getSplash().setTimeout(Integer.parseInt(splashTimeout));	
				}
				_config.getSplash().setTimeoutErr("true".equals(
						props.getProperty(Splash.TIMEOUT_ERR)));
			} else {
				_config.setSplash(null);
			}
			setConfigPath(f);
		} catch (IOException e) {
			throw new ConfigPersisterException(e);
		}
	}

	public void save(File f) throws ConfigPersisterException {
		try {
			BufferedWriter w = new BufferedWriter(new FileWriter(f));
	    	_xstream.toXML(_config, w);
	    	w.close();
	    	setConfigPath(f);
		} catch (Exception e) {
			throw new ConfigPersisterException(e);
		}
	}

	private void setConfigPath(File configFile) {
		_configPath = configFile.getAbsoluteFile().getParentFile();
	}

	private class Props {
		final Properties _properties = new Properties();

		public Props(File f) throws IOException {
			FileInputStream is = null;
			try {
				is = new FileInputStream(f);
				_properties.load(is);
			} finally {
				Util.close(is);
			}
		}

		/**
		 * Get property and remove trailing # comments.
		 */
		public String getProperty(String key) {
			String p = _properties.getProperty(key);
			if (p == null) {
				return null;
			}
			int x = p.indexOf('#');
			if (x == -1) {
				return p;
			}
			do {
				x--;
			} while (x > 0 && (p.charAt(x) == ' ' || p.charAt(x) == '\t'));
			return (x == 0) ? "" : p.substring(0, x + 1);
		}

		public File getFile(String key) {
			String value = getProperty(key);
			return value != null ? new File(value) : null;
		}
	}
}
