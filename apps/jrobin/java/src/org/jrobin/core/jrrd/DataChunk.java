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
 * Models a chunk of result data from an RRDatabase.
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision$
 */
public class DataChunk {

	private static final String NEWLINE = System.getProperty("line.separator");
	long startTime;
	int start;
	int end;
	long step;
	int dsCount;
	double[][] data;
	int rows;

	DataChunk(long startTime, int start, int end, long step, int dsCount, int rows) {
		this.startTime = startTime;
		this.start = start;
		this.end = end;
		this.step = step;
		this.dsCount = dsCount;
		this.rows = rows;
		data = new double[rows][dsCount];
	}

	/**
	 * Returns a summary of the contents of this data chunk. The first column is
	 * the time (RRD format) and the following columns are the data source
	 * values.
	 *
	 * @return a summary of the contents of this data chunk.
	 */
	public String toString() {

		StringBuffer sb = new StringBuffer();
		long time = startTime;

		for (int row = 0; row < rows; row++, time += step) {
			sb.append(time);
			sb.append(": ");

			for (int ds = 0; ds < dsCount; ds++) {
				sb.append(data[row][ds]);
				sb.append(" ");
			}

			sb.append(NEWLINE);
		}

		return sb.toString();
	}
}
