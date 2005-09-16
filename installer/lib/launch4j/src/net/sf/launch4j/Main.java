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
package net.sf.launch4j;

import java.io.File;

import net.sf.launch4j.config.ConfigPersister;
import net.sf.launch4j.config.ConfigPersisterException;
import net.sf.launch4j.formimpl.MainFrame;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class Main {

	public static final String PROGRAM_NAME = "launch4j 2.0.RC3"; 
	public static final String PROGRAM_DESCRIPTION = PROGRAM_NAME + 
		" :: Cross-platform Java application wrapper for creating Windows native executables\n" +
		"Copyright (C) 2005 Grzegorz Kowal\n" +
		"launch4j comes with ABSOLUTELY NO WARRANTY\n" +
	    "This is free software, licensed under the GNU General Public License.\n" +
		"This product includes software developed by the Apache Software Foundation (http://www.apache.org/).\n\n";

	public static void main(String[] args) {
		try {
			if (args.length == 0) {
				ConfigPersister.getInstance().createBlank();
				MainFrame.createInstance();
			} else if (args.length == 1 && !args[0].startsWith("-")) {
				ConfigPersister.getInstance().load(new File(args[0]));
				Builder b = new Builder(Log.getConsoleLog());
				b.build();
			} else {
				System.out.println(PROGRAM_DESCRIPTION + "usage: launch4j config.xml");
			}
		} catch (ConfigPersisterException e) {
			Log.getConsoleLog().append(e.getMessage());
		} catch (BuilderException e) {
			Log.getConsoleLog().append(e.getMessage());
		} 
	}
}
