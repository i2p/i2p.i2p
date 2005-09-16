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
 * Created on May 24, 2005
 */
package net.sf.launch4j.ant;

import java.io.File;

import net.sf.launch4j.Builder;
import net.sf.launch4j.BuilderException;
import net.sf.launch4j.Log;
import net.sf.launch4j.config.Config;
import net.sf.launch4j.config.ConfigPersister;
import net.sf.launch4j.config.ConfigPersisterException;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class Launch4jTask extends Task {
	private File _configFile;
	private AntConfig _config;

	// Override configFile settings
	private File jar;
	private File outfile;
	private String fileVersion;
	private String txtFileVersion;
	private String productVersion;
	private String txtProductVersion;

	public void execute() throws BuildException {
		try {
			if (_configFile != null && _config != null) {
				throw new BuildException("Specify configFile or config");
			} else if (_configFile != null) {
				ConfigPersister.getInstance().load(_configFile);
				Config c = ConfigPersister.getInstance().getConfig();
				if (jar != null) {
					c.setJar(jar);
				}
				if (outfile != null) {
					c.setOutfile(outfile);
				}
				if (fileVersion != null) {
					c.getVersionInfo().setFileVersion(fileVersion);
				}
				if (txtFileVersion != null) {
					c.getVersionInfo().setTxtFileVersion(txtFileVersion);
				}
				if (productVersion != null) {
					c.getVersionInfo().setProductVersion(productVersion);
				}
				if (txtProductVersion != null) {
					c.getVersionInfo().setTxtProductVersion(txtProductVersion);
				}
			} else if (_config != null) {
				ConfigPersister.getInstance().setAntConfig(_config,	getProject().getBaseDir());
			} else {
				throw new BuildException("Specify configFile or config");
			}
			final Builder b = new Builder(Log.getAntLog());
			b.build();
		} catch (ConfigPersisterException e) {
			throw new BuildException(e);
		} catch (BuilderException e) {
			throw new BuildException(e);
		}
	}

	public void setConfigFile(File configFile) {
		_configFile = configFile;
	}

	public void addConfig(AntConfig config) {
		_config = config;
	}

	public void setFileVersion(String fileVersion) {
		this.fileVersion = fileVersion;
	}

	public void setJar(File jar) {
		this.jar = jar;
	}

	public void setOutfile(File outfile) {
		this.outfile = outfile;
	}

	public void setProductVersion(String productVersion) {
		this.productVersion = productVersion;
	}

	public void setTxtFileVersion(String txtFileVersion) {
		this.txtFileVersion = txtFileVersion;
	}

	public void setTxtProductVersion(String txtProductVersion) {
		this.txtProductVersion = txtProductVersion;
	}
}
