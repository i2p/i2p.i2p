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
 * Class DataSourceType
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision$
 */
public class DataSourceType {

	private static final int _COUNTER = 0;
	private static final String STR_COUNTER = "COUNTER";

	/**
	 * Field COUNTER
	 */
	public static final DataSourceType COUNTER = new DataSourceType(_COUNTER);
	private static final int _ABSOLUTE = 1;
	private static final String STR_ABSOLUTE = "ABSOLUTE";

	/**
	 * Field ABSOLUTE
	 */
	public static final DataSourceType ABSOLUTE = new DataSourceType(_ABSOLUTE);
	private static final int _GAUGE = 2;
	private static final String STR_GAUGE = "GAUGE";

	/**
	 * Field GAUGE
	 */
	public static final DataSourceType GAUGE = new DataSourceType(_GAUGE);
	private static final int _DERIVE = 3;
	private static final String STR_DERIVE = "DERIVE";

	/**
	 * Field DERIVE
	 */
	public static final DataSourceType DERIVE = new DataSourceType(_DERIVE);
	private int type;

	private DataSourceType(final int type) {
		this.type = type;
	}

	/**
	 * Returns a <code>DataSourceType</code> with the given name.
	 *
	 * @param s name of the <code>DataSourceType</code> required.
	 * @return a <code>DataSourceType</code> with the given name.
	 */
	public static DataSourceType get(final String s) {

		if (STR_COUNTER.equalsIgnoreCase(s)) {
			return COUNTER;
		}
		else if (STR_ABSOLUTE.equalsIgnoreCase(s)) {
			return ABSOLUTE;
		}
		else if (STR_GAUGE.equalsIgnoreCase(s)) {
			return GAUGE;
		}
		else if (STR_DERIVE.equalsIgnoreCase(s)) {
			return DERIVE;
		}
		else {
			throw new IllegalArgumentException("Invalid DataSourceType");
		}
	}

	/**
	 * Compares this object against the specified object.
	 *
	 * @return <code>true</code> if the objects are the same,
	 *         <code>false</code> otherwise.
	 */
	public boolean equals(final Object obj) {

		if (!(obj instanceof DataSourceType)) {
			throw new IllegalArgumentException("Not a DataSourceType");
		}

		return (((DataSourceType) obj).type == type)
				? true
				: false;
	}

	public int hashCode() {
		return type * 37;
	}

	/**
	 * Returns a string representation of this object.
	 *
	 * @return a string representation of this object.
	 */
	public String toString() {

		String strType;

		switch (type) {

			case _COUNTER:
				strType = STR_COUNTER;
				break;

			case _ABSOLUTE:
				strType = STR_ABSOLUTE;
				break;

			case _GAUGE:
				strType = STR_GAUGE;
				break;

			case _DERIVE:
				strType = STR_DERIVE;
				break;

			default :
				// Don't you just hate it when you see a line like this?
				throw new RuntimeException("This should never happen");
		}

		return strType;
	}
}
