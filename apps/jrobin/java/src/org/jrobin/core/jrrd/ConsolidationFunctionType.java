/*******************************************************************************
 * Copyright (c) 2001-2005 Sasa Markovic and Ciaran Treanor.
 * Copyright (c) 2011 The OpenNMS Group, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *******************************************************************************/
package org.jrobin.core.jrrd;

/**
 * Class ConsolidationFunctionType
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision$
 */
public class ConsolidationFunctionType {

	private static final int _AVERAGE = 0;
	private static final String STR_AVERAGE = "AVERAGE";

	/**
	 * Field AVERAGE
	 */
	public static final ConsolidationFunctionType AVERAGE = new ConsolidationFunctionType(_AVERAGE);
	private static final int _MIN = 1;
	private static final String STR_MIN = "MIN";

	/**
	 * Field MIN
	 */
	public static final ConsolidationFunctionType MIN = new ConsolidationFunctionType(_MIN);
	private static final int _MAX = 2;
	private static final String STR_MAX = "MAX";

	/**
	 * Field MAX
	 */
	public static final ConsolidationFunctionType MAX = new ConsolidationFunctionType(_MAX);
	private static final int _LAST = 3;
	private static final String STR_LAST = "LAST";

	/**
	 * Field LAST
	 */
	public static final ConsolidationFunctionType LAST = new ConsolidationFunctionType(_LAST);
	private int type;

	private ConsolidationFunctionType(int type) {
		this.type = type;
	}

	/**
	 * Returns a <code>ConsolidationFunctionType</code> with the given name.
	 *
	 * @param s name of the <code>ConsolidationFunctionType</code> required.
	 * @return a <code>ConsolidationFunctionType</code> with the given name.
	 */
	public static ConsolidationFunctionType get(final String s) {

		if (STR_AVERAGE.equalsIgnoreCase(s)) {
			return AVERAGE;
		}
		else if (STR_MIN.equalsIgnoreCase(s)) {
			return MIN;
		}
		else if (STR_MAX.equalsIgnoreCase(s)) {
			return MAX;
		}
		else if (STR_LAST.equalsIgnoreCase(s)) {
			return LAST;
		}
		else {
			throw new IllegalArgumentException("Invalid ConsolidationFunctionType: " + s);
		}
	}

	/**
	 * Compares this object against the specified object.
	 *
	 * @return <code>true</code> if the objects are the same,
	 *         <code>false</code> otherwise.
	 */
	public boolean equals(final Object o) {

		if (!(o instanceof ConsolidationFunctionType)) {
			throw new IllegalArgumentException("Not a ConsolidationFunctionType");
		}

		return (((ConsolidationFunctionType) o).type == type)
				? true
				: false;
	}

	public int hashCode() {
		return type * 93;
	}

	/**
	 * Returns a string representation of this object.
	 *
	 * @return a string representation of this object.
	 */
	public String toString() {

		String strType;

		switch (type) {

			case _AVERAGE:
				strType = STR_AVERAGE;
				break;

			case _MIN:
				strType = STR_MIN;
				break;

			case _MAX:
				strType = STR_MAX;
				break;

			case _LAST:
				strType = STR_LAST;
				break;

			default :
				throw new RuntimeException("This should never happen");
		}

		return strType;
	}
}
