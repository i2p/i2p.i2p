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
 * Created on 2005-04-24
 */
package net.sf.launch4j;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import net.sf.launch4j.config.Config;
import net.sf.launch4j.config.ConfigPersister;
import net.sf.launch4j.config.Jre;
import net.sf.launch4j.config.Splash;
import net.sf.launch4j.config.VersionInfo;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class RcBuilder {

	// winnt.h
	public static final int LANG_NEUTRAL = 0;
	public static final int SUBLANG_NEUTRAL	= 0;
	public static final int SUBLANG_DEFAULT	= 1;
	public static final int SUBLANG_SYS_DEFAULT	= 2;

	// ICON
	public static final int APP_ICON = 1;

	// BITMAP
	public static final int SPLASH_BITMAP = 1;

	// RCDATA
	public static final int JRE_PATH = 1;
	public static final int JAVA_MIN_VER = 2;
	public static final int JAVA_MAX_VER = 3;
	public static final int SHOW_SPLASH = 4;
	public static final int SPLASH_WAITS_FOR_WINDOW = 5;
	public static final int SPLASH_TIMEOUT = 6;
	public static final int SPLASH_TIMEOUT_ERR = 7;
	public static final int CHDIR = 8;
	public static final int SET_PROC_NAME = 9;
	public static final int ERR_TITLE = 10;
	public static final int GUI_HEADER_STAYS_ALIVE = 11;
	public static final int JVM_ARGS = 12;
	public static final int JAR_ARGS = 13;

	private final StringBuffer _sb = new StringBuffer();

	public String getContent() {
		return _sb.toString();
	}

	public File build(Config c) throws IOException {
		_sb.append("LANGUAGE ");
		_sb.append(LANG_NEUTRAL);
		_sb.append(", ");
		_sb.append(SUBLANG_DEFAULT);
		_sb.append('\n');
		addVersionInfo(c.getVersionInfo());
		addJre(c.getJre());
		addIcon(APP_ICON, c.getIcon());
		addText(ERR_TITLE, c.getErrTitle());
		addText(JAR_ARGS, c.getJarArgs());
		addWindowsPath(CHDIR, c.getChdir());
		addTrue(SET_PROC_NAME, c.isCustomProcName());
		addTrue(GUI_HEADER_STAYS_ALIVE, c.isStayAlive());
		addSplash(c.getSplash());
		File f = File.createTempFile("launch4j", "rc");
		BufferedWriter w = new BufferedWriter(new FileWriter(f));
		w.write(_sb.toString());
		w.close();
		return f;
	}

	private void addVersionInfo(VersionInfo v) {
		if (v == null) {
			return;
		}
		_sb.append("1 VERSIONINFO\n");
		_sb.append("FILEVERSION ");
		_sb.append(v.getFileVersion().replaceAll("\\.", ", "));
		_sb.append("\nPRODUCTVERSION ");
		_sb.append(v.getProductVersion().replaceAll("\\.", ", "));
		_sb.append("\nFILEFLAGSMASK 0\n" +
				"FILEOS 0x40000\n" +
				"FILETYPE 1\n" +
				"{\n" + 
				" BLOCK \"StringFileInfo\"\n" +
				" {\n" +
				"  BLOCK \"040904E4\"\n" +	// English
				"  {\n");
		addVerBlockValue("CompanyName", v.getCompanyName());
		addVerBlockValue("FileDescription", v.getFileDescription());
		addVerBlockValue("FileVersion", v.getTxtFileVersion());
		addVerBlockValue("InternalName", v.getInternalName());
		addVerBlockValue("LegalCopyright", v.getCopyright());
		addVerBlockValue("OriginalFilename", v.getOriginalFilename());
		addVerBlockValue("ProductName", v.getProductName());
		addVerBlockValue("ProductVersion", v.getTxtProductVersion());
		_sb.append("  }\n }\n}\n");
	}
	
	private void addJre(Jre jre) {
		addWindowsPath(JRE_PATH, jre.getPath());
		addText(JAVA_MIN_VER, jre.getMinVersion());
		addText(JAVA_MAX_VER, jre.getMaxVersion());
		StringBuffer jvmArgs = new StringBuffer();
		if (jre.getInitialHeapSize() > 0) {
			jvmArgs.append("-Xms");
			jvmArgs.append(jre.getInitialHeapSize());
			jvmArgs.append('m');
		}
		if (jre.getMaxHeapSize() > 0) {
			addSpace(jvmArgs);
			jvmArgs.append("-Xmx");
			jvmArgs.append(jre.getMaxHeapSize());
			jvmArgs.append('m');
		}
		if (jre.getArgs() != null && jre.getArgs().length() > 0) {
			addSpace(jvmArgs);
			jvmArgs.append(jre.getArgs().replaceAll("\n", " "));
		}
		addText(JVM_ARGS, jvmArgs.toString());
	}
	
	private void addSplash(Splash splash) {
		if (splash == null) {
			return;
		}
		addTrue(SHOW_SPLASH, true);
		addTrue(SPLASH_WAITS_FOR_WINDOW, splash.getWaitForWindow());
		addText(SPLASH_TIMEOUT, String.valueOf(splash.getTimeout()));
		addTrue(SPLASH_TIMEOUT_ERR, splash.isTimeoutErr());
		addBitmap(SPLASH_BITMAP, splash.getFile());
	}

	private void addText(int id, String text) {
		if (text == null || text.length() == 0) {
			return;
		}
		_sb.append(id);
		_sb.append(" RCDATA BEGIN \"");
		_sb.append(text);
		_sb.append("\\0\" END\n");
	}

	/**
	 * Stores path in Windows format with '\' separators. 
	 */
	private void addWindowsPath(int id, String path) {
		if (path == null || path.equals("")) {
			return;
		}
		_sb.append(id);
		_sb.append(" RCDATA BEGIN \"");
		_sb.append(path.replaceAll("\\\\", "\\\\\\\\")
				.replaceAll("/", "\\\\\\\\"));
		_sb.append("\\0\" END\n");
	}

	private void addTrue(int id, boolean value) {
		if (value) {
			addText(id, "true");
		}
	}

	private void addIcon(int id, File icon) {
		if (icon == null || icon.getPath().equals("")) {
			return;
		}
		_sb.append(id);
		_sb.append(" ICON DISCARDABLE \"");
		_sb.append(getPath(Util.getAbsoluteFile(
				ConfigPersister.getInstance().getConfigPath(), icon)));
		_sb.append("\"\n");
	}

	private void addBitmap(int id, File bitmap) {
		if (bitmap == null) {
			return;
		}
		_sb.append(id);
		_sb.append(" BITMAP \"");
		_sb.append(getPath(Util.getAbsoluteFile(
				ConfigPersister.getInstance().getConfigPath(), bitmap)));
		_sb.append("\"\n");
	}
	
	private String getPath(File f) {
		return f.getPath().replaceAll("\\\\", "\\\\\\\\");
	}
	
	private void addSpace(StringBuffer sb) {
		int len = sb.length();
		if (len-- > 0 && sb.charAt(len) != ' ') {
			sb.append(' ');
		}
	}
	
	private void addVerBlockValue(String key, String value) {
		_sb.append("   VALUE \"");
		_sb.append(key);
		_sb.append("\", \"");
		if (value != null) {
			_sb.append(value);
		}
		_sb.append("\"\n");
	}
}
