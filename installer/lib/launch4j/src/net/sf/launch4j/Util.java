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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class Util {
	public static final boolean WINDOWS_OS = System.getProperty("os.name")
			.toLowerCase().startsWith("windows");

	private Util() {}

	/**
	 * Returns the base directory of a jar file or null if the class is a standalone file. 
	 * @return System specific path
	 * 
	 * Based on a patch submitted by Josh Elsasser
	 */
	public static String getJarBasedir() {
		String url = Util.class.getClassLoader()
				.getResource(Util.class.getName().replace('.', '/') + ".class")
				.getFile()
				.replaceAll("%20", " ");
		if (url.startsWith("file:")) {
			String jar = url.substring(5, url.lastIndexOf('!'));
			int x = jar.lastIndexOf('/');
			if (x == -1) {
				x = jar.lastIndexOf('\\');	
			}
			String basedir = jar.substring(0, x + 1);
			return new File(basedir).getPath();
		} else {
			return null;
		}
	}

	public static File getAbsoluteFile(File basepath, File f) {
		return f.isAbsolute() ? f : new File(basepath, f.getPath());
	}

	public static String getExtension(File f) {
		String name = f.getName();
		int x = name.lastIndexOf('.');
		if (x != -1) {
			return name.substring(x);
		} else {
			return "";
		}
	}

	public static void exec(String cmd, Log log) throws ExecException {
		BufferedReader is = null;
		try {
			if (WINDOWS_OS) {
				cmd = cmd.replaceAll("/", "\\\\");
			}
			Process p = Runtime.getRuntime().exec(cmd);
			is = new BufferedReader(new InputStreamReader(p.getErrorStream()));
			String line;
			while ((line = is.readLine()) != null) {
				log.append(line);
			}
			is.close();
			p.waitFor();
			if (p.exitValue() != 0) {
				String msg = "Exec failed (" + p.exitValue() + "): " + cmd;
				log.append(msg);
				throw new ExecException(msg);
			}
		} catch (IOException e) {
			close(is);
			throw new ExecException(e);
		} catch (InterruptedException e) {
			close(is);
			throw new ExecException(e);
		}
	}

	public static void close(final InputStream o) {
		if (o != null) {
			try {
				o.close();
			} catch (IOException e) {
				System.err.println(e); // XXX log
			}
		}
	}

	public static void close(final OutputStream o) {
		if (o != null) {
			try {
				o.close();
			} catch (IOException e) {
				System.err.println(e); // XXX log
			}
		}
	}

	public static void close(final Reader o) {
		if (o != null) {
			try {
				o.close();
			} catch (IOException e) {
				System.err.println(e); // XXX log
			}
		}
	}

	public static void close(final Writer o) {
		if (o != null) {
			try {
				o.close();
			} catch (IOException e) {
				System.err.println(e); // XXX log
			}
		}
	}

	public static boolean delete(File f) {
		return (f != null) ? f.delete() : false;
	}
}
