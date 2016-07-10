/**
 * Country.java
 *
 * Copyright (C) 2003 MaxMind LLC.  All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package com.maxmind.geoip;

/**
 * Represents a country.
 * 
 * @author Matt Tucker
 */
public class Country {

	private String code;
	private String name;

	/**
	 * Creates a new Country.
	 * 
	 * @param code
	 *            the country code.
	 * @param name
	 *            the country name.
	 */
	public Country(String code, String name) {
		this.code = code;
		this.name = name;
	}

	/**
	 * Returns the ISO two-letter country code of this country.
	 * 
	 * @return the country code.
	 */
	public String getCode() {
		return code;
	}

	/**
	 * Returns the name of this country.
	 * 
	 * @return the country name.
	 */
	public String getName() {
		return name;
	}
}
