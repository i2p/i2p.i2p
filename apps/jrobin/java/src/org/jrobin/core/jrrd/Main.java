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
 * Show some of the things jRRD can do.
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision$
 */
public class Main {

	public Main(String rrdFile) {

		RRDatabase rrd = null;
		DataChunk chunk = null;

		try {
			rrd = new RRDatabase(rrdFile);
			chunk = rrd.getData(ConsolidationFunctionType.AVERAGE);
		} catch (Exception e) {
			e.printStackTrace();

			return;
		}

		try {
			rrd.toXml(System.out);
		} catch (RrdException e) {
			e.printStackTrace();
			return;
		}
		// Dump the database as XML.
		rrd.printInfo(System.out);    // Dump the database header information.
		System.out.println(rrd);      // Dump a summary of the contents of the database.
		System.out.println(chunk);    // Dump the chunk.

		try {
			rrd.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	static void usage(int status) {
		System.err.println("Usage: " + Main.class.getName() + " rrdfile");
		System.exit(status);
	}

	public static void main(String[] args) {
		if (args.length != 1) {
			usage(1);
		}
		new Main(args[0]);
	}
}
