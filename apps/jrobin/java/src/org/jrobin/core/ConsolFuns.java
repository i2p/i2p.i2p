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
 * Simple interface to represent available consolidation functions
 */
public interface ConsolFuns {
	/**
	 * Constant to represent AVERAGE consolidation function
	 */
	public static final String CF_AVERAGE = "AVERAGE";

	/**
	 * Constant to represent MIN consolidation function
	 */
	public static final String CF_MIN = "MIN";

	/**
	 * Constant to represent MAX consolidation function
	 */
	public static final String CF_MAX = "MAX";

	/**
	 * Constant to represent LAST consolidation function
	 */
	public static final String CF_LAST = "LAST";

	/**
	 * Constant to represent FIRST consolidation function
	 */
	public static final String CF_FIRST = "FIRST";

	/**
	 * Constant to represent TOTAL consolidation function
	 */
	public static final String CF_TOTAL = "TOTAL";
}
