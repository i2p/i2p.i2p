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
package org.jrobin.core;

/**
 * Simple interface to represent available datasource types.
 */
public interface DsTypes {
	/**
	 * Constant to represent GAUGE datasource type
	 */
	public static final String DT_GAUGE = "GAUGE";

	/**
	 * Constant to represent COUNTER datasource type
	 */
	public static final String DT_COUNTER = "COUNTER";

	/**
	 * Constant to represent DERIVE datasource type
	 */
	public static final String DT_DERIVE = "DERIVE";

	/**
	 * Constant to represent ABSOLUTE datasource type
	 */
	public static final String DT_ABSOLUTE = "ABSOLUTE";
}
