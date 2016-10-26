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

import java.io.IOException;

import org.jrobin.core.RrdException;

/**
 * Instances of this class model the primary data point status from an RRD file.
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision$
 */
public class PDPStatusBlock {

	long offset;
	long size;
	String lastReading;
	int unknownSeconds;
	double value;

	PDPStatusBlock(RRDFile file) throws IOException,RrdException {

		offset = file.getFilePointer();
		lastReading = file.readString(Constants.LAST_DS_LEN);

		file.align(4);

		unknownSeconds = file.readInt();

		file.align(8); //8 bytes per scratch value in pdp_prep; align on that

		value = file.readDouble();

		// Skip rest of pdp_prep_t.par[]
		file.skipBytes(64);

		size = file.getFilePointer() - offset;
	}

	/**
	 * Returns the last reading from the data source.
	 *
	 * @return the last reading from the data source.
	 */
	public String getLastReading() {
		return lastReading;
	}

	/**
	 * Returns the current value of the primary data point.
	 *
	 * @return the current value of the primary data point.
	 */
	public double getValue() {
		return value;
	}

	/**
	 * Returns the number of seconds of the current primary data point is
	 * unknown data.
	 *
	 * @return the number of seconds of the current primary data point is unknown data.
	 */
	public int getUnknownSeconds() {
		return unknownSeconds;
	}

	/**
	 * Returns a summary the contents of this PDP status block.
	 *
	 * @return a summary of the information contained in this PDP status block.
	 */
	public String toString() {

		StringBuffer sb = new StringBuffer("[PDPStatus: OFFSET=0x");

		sb.append(Long.toHexString(offset));
		sb.append(", SIZE=0x");
		sb.append(Long.toHexString(size));
		sb.append(", lastReading=");
		sb.append(lastReading);
		sb.append(", unknownSeconds=");
		sb.append(unknownSeconds);
		sb.append(", value=");
		sb.append(value);
		sb.append("]");

		return sb.toString();
	}
}
