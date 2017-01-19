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
import java.text.NumberFormat;

import org.jrobin.core.RrdException;

/**
 * Instances of this class model a data source in an RRD file.
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision$
 */
public class DataSource {

	long offset;
	long size;
	String name;
	DataSourceType type;
	int minimumHeartbeat;
	double minimum;
	double maximum;
	PDPStatusBlock pdpStatusBlock;

	DataSource(RRDFile file) throws IOException,RrdException {

		offset = file.getFilePointer();
		name = file.readString(Constants.DS_NAM_SIZE);
		type = DataSourceType.get(file.readString(Constants.DST_SIZE));

		file.align(8);

		minimumHeartbeat = file.readInt(true);

		file.align(8);

		minimum = file.readDouble();
		maximum = file.readDouble();

		// Skip rest of ds_def_t.par[]
		file.align();
		file.skipBytes(56);

		size = file.getFilePointer() - offset;
	}

	void loadPDPStatusBlock(RRDFile file) throws IOException,RrdException {
		pdpStatusBlock = new PDPStatusBlock(file);
	}

	/**
	 * Returns the primary data point status block for this data source.
	 *
	 * @return the primary data point status block for this data source.
	 */
	public PDPStatusBlock getPDPStatusBlock() {
		return pdpStatusBlock;
	}

	/**
	 * Returns the minimum required heartbeat for this data source.
	 *
	 * @return the minimum required heartbeat for this data source.
	 */
	public int getMinimumHeartbeat() {
		return minimumHeartbeat;
	}

	/**
	 * Returns the minimum value input to this data source can have.
	 *
	 * @return the minimum value input to this data source can have.
	 */
	public double getMinimum() {
		return minimum;
	}

	/**
	 * Returns the type this data source is.
	 *
	 * @return the type this data source is.
	 * @see DataSourceType
	 */
	public DataSourceType getType() {
		return type;
	}

	/**
	 * Returns the maximum value input to this data source can have.
	 *
	 * @return the maximum value input to this data source can have.
	 */
	public double getMaximum() {
		return maximum;
	}

	/**
	 * Returns the name of this data source.
	 *
	 * @return the name of this data source.
	 */
	public String getName() {
		return name;
	}

	void printInfo(PrintStream s, NumberFormat numberFormat) {

		StringBuffer sb = new StringBuffer("ds[");

		sb.append(name);
		s.print(sb);
		s.print("].type = \"");
		s.print(type);
		s.println("\"");
		s.print(sb);
		s.print("].minimal_heartbeat = ");
		s.println(minimumHeartbeat);
		s.print(sb);
		s.print("].min = ");
		s.println(Double.isNaN(minimum)
				? "NaN"
				: numberFormat.format(minimum));
		s.print(sb);
		s.print("].max = ");
		s.println(Double.isNaN(maximum)
				? "NaN"
				: numberFormat.format(maximum));
		s.print(sb);
		s.print("].last_ds = ");
		s.println(pdpStatusBlock.lastReading);
		s.print(sb);
		s.print("].value = ");

		double value = pdpStatusBlock.value;

		s.println(Double.isNaN(value)
				? "NaN"
				: numberFormat.format(value));
		s.print(sb);
		s.print("].unknown_sec = ");
		s.println(pdpStatusBlock.unknownSeconds);
	}

	void toXml(PrintStream s) {

		s.println("\t<ds>");
		s.print("\t\t<name> ");
		s.print(name);
		s.println(" </name>");
		s.print("\t\t<type> ");
		s.print(type);
		s.println(" </type>");
		s.print("\t\t<minimal_heartbeat> ");
		s.print(minimumHeartbeat);
		s.println(" </minimal_heartbeat>");
		s.print("\t\t<min> ");
		s.print(minimum);
		s.println(" </min>");
		s.print("\t\t<max> ");
		s.print(maximum);
		s.println(" </max>");
		s.println();
		s.println("\t\t<!-- PDP Status -->");
		s.print("\t\t<last_ds> ");
		s.print(pdpStatusBlock.lastReading);
		s.println(" </last_ds>");
		s.print("\t\t<value> ");
		s.print(pdpStatusBlock.value);
		s.println(" </value>");
		s.print("\t\t<unknown_sec> ");
		s.print(pdpStatusBlock.unknownSeconds);
		s.println(" </unknown_sec>");
		s.println("\t</ds>");
		s.println();
	}

	/**
	 * Returns a summary the contents of this data source.
	 *
	 * @return a summary of the information contained in this data source.
	 */
	public String toString() {

		StringBuffer sb = new StringBuffer("[DataSource: OFFSET=0x");

		sb.append(Long.toHexString(offset));
		sb.append(", SIZE=0x");
		sb.append(Long.toHexString(size));
		sb.append(", name=");
		sb.append(name);
		sb.append(", type=");
		sb.append(type.toString());
		sb.append(", minHeartbeat=");
		sb.append(minimumHeartbeat);
		sb.append(", min=");
		sb.append(minimum);
		sb.append(", max=");
		sb.append(maximum);
		sb.append("]");
		sb.append("\n\t\t");
		sb.append(pdpStatusBlock.toString());

		return sb.toString();
	}
}
