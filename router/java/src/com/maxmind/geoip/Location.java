/**
 * Location.java
 *
 * Copyright (C) 2004 MaxMind LLC.  All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Lesser Public
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

public class Location {
	public String countryCode;
	public String countryName;
	public String region;
	public String city;
	public String postalCode;
	public float latitude;
	public float longitude;
	public int dma_code;
	public int area_code;
	public int metro_code;

	private final static double EARTH_DIAMETER = 2 * 6378.2;
	private final static double PI = 3.14159265;
	private final static double RAD_CONVERT = PI / 180;

	public double distance(Location loc) {
		double delta_lat, delta_lon;
		double temp;

		float lat1 = latitude;
		float lon1 = longitude;
		float lat2 = loc.latitude;
		float lon2 = loc.longitude;

		// convert degrees to radians
		lat1 *= RAD_CONVERT;
		lat2 *= RAD_CONVERT;

		// find the deltas
		delta_lat = lat2 - lat1;
		delta_lon = (lon2 - lon1) * RAD_CONVERT;

		// Find the great circle distance
		temp = Math.pow(Math.sin(delta_lat / 2), 2) + Math.cos(lat1)
				* Math.cos(lat2) * Math.pow(Math.sin(delta_lon / 2), 2);
		return EARTH_DIAMETER
				* Math.atan2(Math.sqrt(temp), Math.sqrt(1 - temp));
	}
}
