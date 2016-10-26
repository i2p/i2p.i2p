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
 * Instances of this class model the header section of an RRD file.
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision$
 */
public class Header implements Constants {

	static final long offset = 0;
	long size;
	String version;
	int intVersion;
	int dsCount;
	int rraCount;
	int pdpStep;

	Header(RRDFile file) throws IOException,RrdException {

		if (!file.readString(4).equals(COOKIE)) {
			throw new IOException("Invalid COOKIE");
		}

		version = file.readString(5);
		intVersion = Integer.parseInt(version);
		if( intVersion > 3 ) {
			throw new IOException("Unsupported RRD version (" + version + ")");
		}

		file.align();

		// Consume the FLOAT_COOKIE
		file.readDouble();

		dsCount = file.readInt();
		rraCount = file.readInt();
		pdpStep = file.readInt();

		// Skip rest of stat_head_t.par
		file.align();
		file.skipBytes(80);

		size = file.getFilePointer() - offset;
	}

	/**
	 * Returns the version of the database.
	 *
	 * @return the version of the database.
	 */
	public String getVersion() {
		return version;
	}

	public int getIntVersion() {
		return intVersion;
	}

	/**
	 * Returns the number of <code>DataSource</code>s in the database.
	 *
	 * @return the number of <code>DataSource</code>s in the database.
	 */
	public int getDSCount() {
		return dsCount;
	}

	/**
	 * Returns the number of <code>Archive</code>s in the database.
	 *
	 * @return the number of <code>Archive</code>s in the database.
	 */
	public int getRRACount() {
		return rraCount;
	}

	/**
	 * Returns the primary data point interval in seconds.
	 *
	 * @return the primary data point interval in seconds.
	 */
	public int getPDPStep() {
		return pdpStep;
	}

	/**
	 * Returns a summary the contents of this header.
	 *
	 * @return a summary of the information contained in this header.
	 */
	public String toString() {

		StringBuffer sb = new StringBuffer("[Header: OFFSET=0x00, SIZE=0x");

		sb.append(Long.toHexString(size));
		sb.append(", version=");
		sb.append(version);
		sb.append(", dsCount=");
		sb.append(dsCount);
		sb.append(", rraCount=");
		sb.append(rraCount);
		sb.append(", pdpStep=");
		sb.append(pdpStep);
		sb.append("]");

		return sb.toString();
	}
}
