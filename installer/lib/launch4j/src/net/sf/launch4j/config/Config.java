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
 * Created on Apr 21, 2005
 */
package net.sf.launch4j.config;

import java.io.File;
import java.util.List;

import net.sf.launch4j.binding.IValidatable;
import net.sf.launch4j.binding.Validator;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class Config implements IValidatable {

	public static final String HEADER = "header";
	public static final String JAR = "jar";
	public static final String OUTFILE = "outfile";
	public static final String ERR_TITLE = "errTitle";
	public static final String JAR_ARGS = "jarArgs";
	public static final String CHDIR = "chdir";
	public static final String CUSTOM_PROC_NAME = "customProcName";
	public static final String STAY_ALIVE = "stayAlive";
	public static final String ICON = "icon";

	public static final int GUI_HEADER = 0;
	public static final int CONSOLE_HEADER = 1;
	public static final int CUSTOM_HEADER = 2;

	private int headerType;
	private List headerObjects;
	private List libs;
	private File jar;
	private File outfile;

	// runtime configuration
	private String errTitle;
	private String jarArgs;
	private String chdir;
	private boolean customProcName = false;
	private boolean stayAlive = false;
	private File icon;
	private Jre jre;
	private Splash splash;
	private VersionInfo versionInfo;

	public void checkInvariants() {
		Validator.checkTrue(outfile != null && outfile.getPath().endsWith(".exe"),
				"outfile", "Specify output file with .exe extension.");
		Validator.checkFile(jar, "jar", "Application jar");
		if (!Validator.isEmpty(chdir)) {
			Validator.checkRelativeWinPath(chdir,
					"chdir", "'chdir' must be a path relative to the executable.");
			Validator.checkFalse(chdir.toLowerCase().equals("true")
					|| chdir.toLowerCase().equals("false"),
					"chdir", "'chdir' is now a path instead of a boolean, please check the docs.");
		}
		Validator.checkOptFile(icon, "icon", "Icon");
		Validator.checkOptString(jarArgs, 128, "jarArgs", "Jar arguments");
		Validator.checkOptString(errTitle, 128, "errTitle", "Error title");
		Validator.checkRange(headerType, GUI_HEADER, CONSOLE_HEADER,
				"headerType", "Invalid header type");	// TODO add custom header check
		Validator.checkTrue(headerType != CONSOLE_HEADER || splash == null,
				"headerType", "Splash screen is not implemented by console header.");
		// TODO libs
		jre.checkInvariants();
	}
	
	public void validate() {
		checkInvariants();
		if (splash != null) {
			splash.checkInvariants();
		}
		if (versionInfo != null) {
			versionInfo.checkInvariants();
		}
	}

	/** Change current directory to EXE location. */
	public String getChdir() {
		return chdir;
	}

	public void setChdir(String chdir) {
		this.chdir = chdir;
	}

	/** Constant command line arguments passed to application jar. */
	public String getJarArgs() {
		return jarArgs;
	}

	public void setJarArgs(String jarArgs) {
		this.jarArgs = jarArgs;
	}

	/** Optional, error message box title. */
	public String getErrTitle() {
		return errTitle;
	}

	public void setErrTitle(String errTitle) {
		this.errTitle = errTitle;
	}

	/** launch4j header file. */
	public int getHeaderType() {
		return headerType;
	}

	public void setHeaderType(int headerType) {
		this.headerType = headerType;
	}
	
	public List getHeaderObjects() {
//		switch (_headerType) {
//		case GUI_HEADER:
//			return
//		case CONSOLE_HEADER:
// FIXME	
//		default:
			return headerObjects;
	}
	
	public void setHeaderObject(List headerObjects) {
		this.headerObjects = headerObjects;
	}
	
	public List getLibs() {
		return libs;
		// FIXME default libs if null or empty
	}
	
	public void setLibs(List libs) {
		this.libs = libs;
	}

	/** ICO file. */
	public File getIcon() {
		return icon;
	}

	public void setIcon(File icon) {
		this.icon = icon;
	}

	/** Jar to wrap. */
	public File getJar() {
		return jar;
	}

	public void setJar(File jar) {
		this.jar = jar;
	}

	/** JRE configuration */
	public Jre getJre() {
		return jre;
	}

	public void setJre(Jre jre) {
		this.jre = jre;
	}

	/** Output EXE file. */
	public File getOutfile() {
		return outfile;
	}

	public void setOutfile(File outfile) {
		this.outfile = outfile;
	}

	/** Custom process name as the output EXE file name. */
	public boolean isCustomProcName() {
		return customProcName;
	}

	public void setCustomProcName(boolean customProcName) {
		this.customProcName = customProcName;
	}

	/** Splash screen configuration. */
	public Splash getSplash() {
		return splash;
	}

	public void setSplash(Splash splash) {
		this.splash = splash;
	}

	/** Stay alive after launching the application. */
	public boolean isStayAlive() {
		return stayAlive;
	}

	public void setStayAlive(boolean stayAlive) {
		this.stayAlive = stayAlive;
	}
	
	public VersionInfo getVersionInfo() {
		return versionInfo;
	}

	public void setVersionInfo(VersionInfo versionInfo) {
		this.versionInfo = versionInfo;
	}
}
