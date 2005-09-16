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
 * Created on 2004-01-15
 */
package net.sf.launch4j;

import java.io.File;

import javax.swing.filechooser.FileFilter;

/**
 * @author Copyright (C) 2004 Grzegorz Kowal
 */
public class FileChooserFilter extends FileFilter {
	String _description;
	String[] _extensions;

	public FileChooserFilter(String description, String extension) {
		_description = description;
		_extensions = new String[] {extension};
	}
	
	public FileChooserFilter(String description, String[] extensions) {
		_description = description;
		_extensions = extensions;
	}

	public boolean accept(File f) {
		if (f.isDirectory()) {
			return true;
		}
		String ext = Util.getExtension(f);
		for (int i = 0; i < _extensions.length; i++) {
			if (ext.toLowerCase().equals(_extensions[i].toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	public String getDescription() {
		return _description;
	}
}
