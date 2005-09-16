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
 * Created on May 12, 2005
 */
package net.sf.launch4j;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public abstract class Log {
	private static final Log _consoleLog = new ConsoleLog();
	private static final Log _antLog = new AntLog();

	public abstract void clear();
	public abstract void append(String line);

	public static Log getConsoleLog() {
		return _consoleLog;
	}
	
	public static Log getAntLog() {
		return _antLog;
	}

	public static Log getSwingLog(JTextArea textArea) {
		return new SwingLog(textArea);
	}
}

class ConsoleLog extends Log {
	public void clear() {
		System.out.println("\n");
	}

	public void append(String line) {
		System.out.println("launch4j: " + line);
	}
}

class AntLog extends Log {
	public void clear() {
		System.out.println("\n");
	}

	public void append(String line) {
		System.out.println(line);
	}
}

class SwingLog extends Log {
	private final JTextArea _textArea;

	public SwingLog(JTextArea textArea) {
		_textArea = textArea;
	}

	public void clear() {
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				_textArea.setText("");
		}});
	}

	public void append(final String line) {
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				_textArea.append(line + "\n");
		}});
	}
}
