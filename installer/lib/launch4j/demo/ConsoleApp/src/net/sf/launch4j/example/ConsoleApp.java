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

package net.sf.launch4j.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class ConsoleApp {
    public static void main(String[] args) {
		StringBuffer sb = new StringBuffer("Hello World!\n\nJava version: ");
		sb.append(System.getProperty("java.version"));
		sb.append("\nJava home: ");
		sb.append(System.getProperty("java.home"));
		sb.append("\nCurrent dir: ");
		sb.append(System.getProperty("user.dir"));
		if (args.length > 0) {
			sb.append("\nArgs: ");
			for (int i = 0; i < args.length; i++) {
				sb.append(args[i]);
				sb.append(' ');
			}
		}
		sb.append("\n\nEnter a line of text, Ctrl-C to stop.\n\n>");
		System.out.print(sb.toString());
		try {
			BufferedReader is = new BufferedReader(new InputStreamReader(System.in));
			String line;
			while ((line = is.readLine()) != null) {
				System.out.print("You wrote: " + line + "\n\n>");
			}
			is.close();
		} catch (IOException e) {
			System.err.print(e);
		}
    }
}
