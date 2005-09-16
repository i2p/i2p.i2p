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

import org.apache.tools.ant.BuildException;

import net.sf.launch4j.config.Config;
import net.sf.launch4j.config.Jre;
import net.sf.launch4j.config.Splash;
import net.sf.launch4j.config.VersionInfo;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class AntConfig extends Config {

	public void addJre(Jre jre) {
		checkNull(getJre(), "jre");
		setJre(jre);
	}

	public void addSplash(Splash splash) {
		checkNull(getSplash(), "splash");
		setSplash(splash);
	}

	public void addVersionInfo(VersionInfo versionInfo) {
		checkNull(getVersionInfo(), "versionInfo");
		setVersionInfo(versionInfo);
	}

	private void checkNull(Object o, String name) {
		if (o != null) {
			throw new BuildException("Duplicate element: " + name);
		}
	}
}
