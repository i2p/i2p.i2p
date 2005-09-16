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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import net.sf.launch4j.binding.InvariantViolationException;
import net.sf.launch4j.config.Config;
import net.sf.launch4j.config.ConfigPersister;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class Builder {
	private final Log _log;

	public Builder(Log log) {
		_log = log;
	}

	/**
	 * @return Output file path.
	 */
	public File build() throws BuilderException {
		final Config c = ConfigPersister.getInstance().getConfig();
		try {
			c.validate();
		} catch (InvariantViolationException e) {
			throw new BuilderException(e.getMessage());
		}
		File rc = null;
		File ro = null;
		File outfile = null;
		FileInputStream is = null;
		FileOutputStream os = null;
		final RcBuilder rcb = new RcBuilder();
		try {
			String basedir = Util.getJarBasedir();
			if (basedir == null) {
				basedir = ".";
			}
			rc = rcb.build(c);
			ro = File.createTempFile("launch4j", "o");
			outfile = ConfigPersister.getInstance().getOutputFile();

			Cmd resCmd = new Cmd(basedir);
			resCmd.addExe("/bin/windres")
					.add(Util.WINDOWS_OS ? "--preprocessor=type" : "--preprocessor=cat")
					.add("-J rc -O coff -F pe-i386")
					.add(rc.getPath())
					.add(ro.getPath());
			_log.append("Compiling resources");
			Util.exec(resCmd.toString(), _log);

			Cmd ldCmd = new Cmd(basedir);
			ldCmd.addExe("/bin/ld")
					.add("-mi386pe")
					.add("--oformat pei-i386")
					.add((c.getHeaderType() == Config.GUI_HEADER)
							? "--subsystem windows" : "--subsystem console")
					.add("-s")		// strip symbols
					.addFile("/w32api/crt2.o")
					.addFile((c.getHeaderType() == Config.GUI_HEADER)
							? "/head/guihead.o" : "/head/consolehead.o")
					.addFile("/head/head.o")
					.addAbsFile(ro.getPath())
					.addFile("/w32api/libmingw32.a")
					.addFile("/w32api/libgcc.a")
					.addFile("/w32api/libmsvcrt.a")
					.addFile("/w32api/libkernel32.a")
					.addFile("/w32api/libuser32.a")
					.addFile("/w32api/libadvapi32.a")
					.addFile("/w32api/libshell32.a")
					.add("-o")
					.addAbsFile(outfile.getPath());
			_log.append("Linking");
			Util.exec(ldCmd.toString(), _log);

			_log.append("Wrapping");
			int len;
			byte[] buffer = new byte[1024];
			is = new FileInputStream(
					Util.getAbsoluteFile(ConfigPersister.getInstance().getConfigPath(),	c.getJar()));
			os = new FileOutputStream(outfile, true);
			while ((len = is.read(buffer)) > 0) {
				os.write(buffer, 0, len);
			}
			_log.append("Successfully created " + outfile.getPath());
			return outfile;
		} catch (IOException e) {
			Util.delete(outfile);
			_log.append(e.getMessage());
			throw new BuilderException(e);
		} catch (ExecException e) {
			Util.delete(outfile);
			String msg = e.getMessage(); 
			if (msg != null && msg.indexOf("windres") != -1) {
				_log.append("Generated resource file...\n");
				_log.append(rcb.getContent());
			}
			throw new BuilderException(e);
		} finally {
			Util.close(is);
			Util.close(os);
			Util.delete(rc);
			Util.delete(ro);
		}
	}
}

class Cmd {
	private final StringBuffer _sb = new StringBuffer();
	private final String _basedir;
	private final boolean _quote;

	public Cmd(String basedir) {
		_basedir = basedir;
		_quote = basedir.indexOf(' ') != -1;
	}

	public Cmd add(String s) {
		space();
		_sb.append(s);
		return this;
	}

	public Cmd addAbsFile(String file) {
		space();
		boolean quote = file.indexOf(' ') != -1;
		if (quote) {
			_sb.append('"');
		}
		_sb.append(file);
		if (quote) {
			_sb.append('"');
		}
		return this;
	}

	public Cmd addFile(String file) {
		space();
		if (_quote) {
			_sb.append('"');
		}
		_sb.append(_basedir);
		_sb.append(file);
		if (_quote) {
			_sb.append('"');
		}
		return this;
	}

	public Cmd addExe(String file) {
		return addFile(Util.WINDOWS_OS ? file + ".exe" : file);
	}

	private void space() {
		if (_sb.length() > 0) {
			_sb.append(' ');
		}
	}
	
	public String toString() {
		return _sb.toString();
	}
}
