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

interface Constants {

	int DS_NAM_SIZE = 20;
	int DST_SIZE = 20;
	int CF_NAM_SIZE = 20;
	int LAST_DS_LEN = 30;
	static String COOKIE = "RRD";
	static String VERSION = "0001";
	static String VERSION3 = "0003";
	double FLOAT_COOKIE = 8.642135E130;
	static byte[] FLOAT_COOKIE_BIG_ENDIAN = {0x5B, 0x1F, 0x2B, 0x43,
			(byte) 0xC7, (byte) 0xC0, 0x25,
			0x2F};
	static byte[] FLOAT_COOKIE_LITTLE_ENDIAN = {0x2F, 0x25, (byte) 0xC0,
			(byte) 0xC7, 0x43, 0x2B, 0x1F,
			0x5B};
}
