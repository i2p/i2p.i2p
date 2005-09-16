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
 * Created on 2004-01-30
 */
package net.sf.launch4j.binding;

import java.io.File;
import java.util.Collection;
import java.util.HashSet;

import net.sf.launch4j.Util;
import net.sf.launch4j.config.ConfigPersister;

/**
 * @author Copyright (C) 2004 Grzegorz Kowal
 */
public class Validator {
	public static final String ALPHANUMERIC_PATTERN = "[\\w]*?";
	public static final String ALPHA_PATTERN = "[\\w&&\\D]*?";
	public static final String NUMERIC_PATTERN = "[\\d]*?";
	public static final String PATH_PATTERN = "[\\w|[ .,:\\-/\\\\]]*?";

	private static final String EMPTY_FIELD = "Enter: ";
	private static final String FIELD_ERROR = "Invalid data: ";
	private static final String RANGE_ERROR = " must be in range [";
	private static final String MINIMUM = " must be at least ";
	private static final String DUPLICATE = " already exists.";

	private Validator() {}

	public static boolean isEmpty(String s) {
		return s == null || s.equals("");
	}

	public static void checkNotNull(Object o, String property, String name) {
		if (o == null) {
			signalViolation(property, EMPTY_FIELD + name);
		}
	}

	public static void checkString(String s, int maxLength, String property, String name) {
		if (s == null || s.length() == 0) {
			signalViolation(property, EMPTY_FIELD + name);
		}
		if (s.length() > maxLength) {
			signalLengthViolation(property, name, maxLength);
		}
	}

	public static void checkString(String s, int maxLength, String pattern,
			String property, String name) {
		checkString(s, maxLength, property, name);
		if (!s.matches(pattern)) {
			signalViolation(property, FIELD_ERROR + name);
		}
	}

	public static void checkOptString(String s, int maxLength, String property, String name) {
		if (s == null || s.length() == 0) {
			return;
		}
		if (s.length() > maxLength) {
			signalLengthViolation(property, name, maxLength);
		}
	}

	public static void checkOptString(String s, int maxLength, String pattern,
			String property, String name) {
		if (s == null || s.length() == 0) {
			return;
		}
		if (s.length() > maxLength) {
			signalLengthViolation(property, name, maxLength);
		}
		if (!s.matches(pattern)) {
			signalViolation(property, FIELD_ERROR + name);
		}
	}

	public static void checkRange(int value, int min, int max,
			String property, String name) {
		if (value < min || value > max) {
			StringBuffer sb = new StringBuffer(name);
			sb.append(RANGE_ERROR);
			sb.append(min);
			sb.append('-');
			sb.append(max);
			sb.append(']');
			signalViolation(property, sb.toString());			
		}
	}

	public static void checkRange(char value, char min, char max,
			String property, String name) {
		if (value < min || value > max) {
			StringBuffer sb = new StringBuffer(name);
			sb.append(RANGE_ERROR);
			sb.append(min);
			sb.append('-');
			sb.append(max);
			sb.append(']');
			signalViolation(property, sb.toString());			
		}
	}

	public static void checkMin(int value, int min, String property, String name) {
		if (value < min) {
			StringBuffer sb = new StringBuffer(name);
			sb.append(MINIMUM);
			sb.append(min);
			signalViolation(property, sb.toString());
		}
	}

	public static void checkTrue(boolean condition, String property, String msg) {
		if (!condition) {
			signalViolation(property, msg);
		}
	}
	
	public static void checkFalse(boolean condition, String property, String msg) {
		if (condition) {
			signalViolation(property, msg);
		}
	}
	
	public static void checkElementsNotNullUnique(Collection c, String property, String msg) {
		if (c.contains(null)
				|| new HashSet(c).size() != c.size()) {
			signalViolation(property, msg + DUPLICATE);
		}
	}

	public static void checkElementsUnique(Collection c, String property, String msg) {
		if (new HashSet(c).size() != c.size()) {
			signalViolation(property, msg + DUPLICATE);
		}
	}

	public static void checkFile(File f, String property, String fileDescription) {
		File cfgPath = ConfigPersister.getInstance().getConfigPath();
		if (f == null || (!f.exists() && !Util.getAbsoluteFile(cfgPath, f).exists())) {
			signalViolation(property, fileDescription + " doesn't exist.");
		}
	}

	public static void checkOptFile(File f, String property, String fileDescription) {
		if (f != null && f.getPath().length() > 0) {
			checkFile(f, property, fileDescription);
		}
	}

	public static void checkRelativeWinPath(String path, String property, String msg) {
		if (path.startsWith("/")
				|| path.startsWith("\\")
				|| path.indexOf(':') != -1) {
			signalViolation(property, msg);
		}
	}

	public static void signalLengthViolation(String property, String name, int maxLength)	{
		final StringBuffer sb = new StringBuffer(name);
		sb.append(" exceeds the maximum length of ");
		sb.append(maxLength);
		sb.append(" characters.");
		throw new InvariantViolationException(property, sb.toString());
	}

	public static void signalViolation(String property, String msg)	{
		throw new InvariantViolationException(property, msg);
	}
}
