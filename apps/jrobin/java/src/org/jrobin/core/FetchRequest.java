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

import java.io.IOException;
import java.util.Set;

/**
 * Class to represent fetch request. For the complete explanation of all
 * fetch parameters consult RRDTool's
 * <a href="../../../../man/rrdfetch.html" target="man">rrdfetch man page</a>.
 * <p>
 * You cannot create <code>FetchRequest</code> directly (no public constructor
 * is provided). Use {@link RrdDb#createFetchRequest(String, long, long, long)
 * createFetchRequest()} method of your {@link RrdDb RrdDb} object.
 *
 * @author <a href="mailto:saxon@jrobin.org">Sasa Markovic</a>
 */
public class FetchRequest {
	private RrdDb parentDb;
	private String consolFun;
	private long fetchStart;
	private long fetchEnd;
	private long resolution;
	private String[] filter;

	public FetchRequest(RrdDb parentDb, String consolFun, long fetchStart, long fetchEnd, long resolution) throws RrdException {
		this.parentDb = parentDb;
		this.consolFun = consolFun;
		this.fetchStart = fetchStart;
		this.fetchEnd = fetchEnd;
		this.resolution = resolution;
		validate();
	}

	/**
	 * Sets request filter in order to fetch data only for
	 * the specified array of datasources (datasource names).
	 * If not set (or set to null), fetched data will
	 * containt values of all datasources defined in the corresponding RRD.
	 * To fetch data only from selected
	 * datasources, specify an array of datasource names as method argument.
	 *
	 * @param filter Array of datsources (datsource names) to fetch data from.
	 */
	public void setFilter(String[] filter) {
		this.filter = filter;
	}

	/**
	 * Sets request filter in order to fetch data only for
	 * the specified set of datasources (datasource names).
	 * If the filter is not set (or set to null), fetched data will
	 * containt values of all datasources defined in the corresponding RRD.
	 * To fetch data only from selected
	 * datasources, specify a set of datasource names as method argument.
	 *
	 * @param filter Set of datsource names to fetch data for.
	 */
	public void setFilter(Set<String> filter) {
		this.filter = filter.toArray(new String[0]);
	}

	/**
	 * Sets request filter in order to fetch data only for
	 * a single datasource (datasource name).
	 * If not set (or set to null), fetched data will
	 * containt values of all datasources defined in the corresponding RRD.
	 * To fetch data for a single datasource only,
	 * specify an array of datasource names as method argument.
	 *
	 * @param filter Array of datsources (datsource names) to fetch data from.
	 */
	public void setFilter(String filter) {
		this.filter = (filter == null) ? null : (new String[] {filter});
	}

	/**
	 * Returns request filter. See {@link #setFilter(String[]) setFilter()} for
	 * complete explanation.
	 *
	 * @return Request filter (array of datasource names), null if not set.
	 */
	public String[] getFilter() {
		return filter;
	}

	/**
	 * Returns consolitation function to be used during the fetch process.
	 *
	 * @return Consolidation function.
	 */
	public String getConsolFun() {
		return consolFun;
	}

	/**
	 * Returns starting timestamp to be used for the fetch request.
	 *
	 * @return Starting timstamp in seconds.
	 */
	public long getFetchStart() {
		return fetchStart;
	}

	/**
	 * Returns ending timestamp to be used for the fetch request.
	 *
	 * @return Ending timestamp in seconds.
	 */
	public long getFetchEnd() {
		return fetchEnd;
	}

	/**
	 * Returns fetch resolution to be used for the fetch request.
	 *
	 * @return Fetch resolution in seconds.
	 */
	public long getResolution() {
		return resolution;
	}

	private void validate() throws RrdException {
		if (!ArcDef.isValidConsolFun(consolFun)) {
			throw new RrdException("Invalid consolidation function in fetch request: " + consolFun);
		}
		if (fetchStart < 0) {
			throw new RrdException("Invalid start time in fetch request: " + fetchStart);
		}
		if (fetchEnd < 0) {
			throw new RrdException("Invalid end time in fetch request: " + fetchEnd);
		}
		if (fetchStart > fetchEnd) {
			throw new RrdException("Invalid start/end time in fetch request: " + fetchStart +
					" > " + fetchEnd);
		}
		if (resolution <= 0) {
			throw new RrdException("Invalid resolution in fetch request: " + resolution);
		}
	}

	/**
	 * Dumps the content of fetch request using the syntax of RRDTool's fetch command.
	 *
	 * @return Fetch request dump.
	 */
	public String dump() {
		return "fetch \"" + parentDb.getRrdBackend().getPath() +
				"\" " + consolFun + " --start " + fetchStart + " --end " + fetchEnd +
				(resolution > 1 ? " --resolution " + resolution : "");
	}

	String getRrdToolCommand() {
		return dump();
	}

	/**
	 * Returns data from the underlying RRD and puts it in a single
	 * {@link FetchData FetchData} object.
	 *
	 * @return FetchData object filled with timestamps and datasource values.
	 * @throws RrdException Thrown in case of JRobin specific error.
	 * @throws IOException  Thrown in case of I/O error.
	 */
	public FetchData fetchData() throws RrdException, IOException {
		return parentDb.fetchData(this);
	}

	/**
	 * Returns the underlying RrdDb object.
	 *
	 * @return RrdDb object used to create this FetchRequest object.
	 */
	public RrdDb getParentDb() {
		return parentDb;
	}

}
