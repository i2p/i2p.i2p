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
import java.io.PrintStream;

import org.jrobin.core.RrdException;

/**
 * Instances of this class model the consolidation data point status from an RRD file.
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision$
 */
public class CDPStatusBlock {

	long offset;
	long size;
	int unknownDatapoints;
	double value;

	CDPStatusBlock(RRDFile file) throws IOException, RrdException {

		offset = file.getFilePointer();
		value = file.readDouble();
		unknownDatapoints = file.readInt();
		file.align(8);
		// Skip rest of cdp_prep_t.scratch
		file.skipBytes(64);

		size = file.getFilePointer() - offset;
	}

	/**
	 * Returns the number of unknown primary data points that were integrated.
	 *
	 * @return the number of unknown primary data points that were integrated.
	 */
	public int getUnknownDatapoints() {
		return unknownDatapoints;
	}

	/**
	 * Returns the value of this consolidated data point.
	 *
	 * @return the value of this consolidated data point.
	 */
	public double getValue() {
		return value;
	}

	void toXml(PrintStream s) {

		s.print("\t\t\t<ds><value> ");
		s.print(value);
		s.print(" </value>  <unknown_datapoints> ");
		s.print(unknownDatapoints);
		s.println(" </unknown_datapoints></ds>");
	}

	/**
	 * Returns a summary the contents of this CDP status block.
	 *
	 * @return a summary of the information contained in the CDP status block.
	 */
	public String toString() {

		StringBuffer sb = new StringBuffer("[CDPStatusBlock: OFFSET=0x");

		sb.append(Long.toHexString(offset));
		sb.append(", SIZE=0x");
		sb.append(Long.toHexString(size));
		sb.append(", unknownDatapoints=");
		sb.append(unknownDatapoints);
		sb.append(", value=");
		sb.append(value);
		sb.append("]");

		return sb.toString();
	}
}
