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

/**
 * Class to represent RRD header. Header information is mainly static (once set, it
 * cannot be changed), with the exception of last update time (this value is changed whenever
 * RRD gets updated).
 * <p>
 * Normally, you don't need to manipulate the Header object directly - JRobin framework
 * does it for you.
 *
 * @author <a href="mailto:saxon@jrobin.org">Sasa Markovic</a>*
 */
public class Header implements RrdUpdater {
	static final int SIGNATURE_LENGTH = 2;
	static final String SIGNATURE = "JR";

	static final String DEFAULT_SIGNATURE = "JRobin, version 0.1";
	static final String RRDTOOL_VERSION = "0001";

	private RrdDb parentDb;

	private RrdString signature;
	private RrdLong step;
	private RrdInt dsCount, arcCount;
	private RrdLong lastUpdateTime;
	private Long m_primitiveStep = null;
	private Integer m_primitiveDsCount = null;
	private Integer m_primitiveArcCount = null;

	Header(final RrdDb parentDb, final RrdDef rrdDef) throws IOException {
	    final boolean shouldInitialize = rrdDef != null;
		this.parentDb = parentDb;
		signature = new RrdString(this);			 // NOT constant, may NOT be cached
		step = new RrdLong(this, true);			 // constant, may be cached
		dsCount = new RrdInt(this, true);			 // constant, may be cached
		arcCount = new RrdInt(this, true);			 // constant, may be cached
		lastUpdateTime = new RrdLong(this);
		if (shouldInitialize) {
			signature.set(DEFAULT_SIGNATURE);
			step.set(rrdDef.getStep());
			dsCount.set(rrdDef.getDsCount());
			arcCount.set(rrdDef.getArcCount());
			lastUpdateTime.set(rrdDef.getStartTime());
		}
	}

	Header(final RrdDb parentDb, final DataImporter reader) throws IOException, RrdException {
		this(parentDb, (RrdDef) null);
		final String version = reader.getVersion();
		final int intVersion = Integer.parseInt(version);
		if (intVersion > 3) {
			throw new RrdException("Could not unserialize xml version " + version);
		}
		signature.set(DEFAULT_SIGNATURE);
		step.set(reader.getStep());
		dsCount.set(reader.getDsCount());
		arcCount.set(reader.getArcCount());
		lastUpdateTime.set(reader.getLastUpdateTime());
	}

	/**
	 * Returns RRD signature. Initially, the returned string will be
	 * of the form <b><i>JRobin, version x.x</i></b>. Note: RRD format did not
	 * change since Jrobin 1.0.0 release (and probably never will).
	 *
	 * @return RRD signature
	 * @throws IOException Thrown in case of I/O error
	 */
	public String getSignature() throws IOException {
		return signature.get();
	}

	public String getInfo() throws IOException {
		return getSignature().substring(SIGNATURE_LENGTH);
	}

	public void setInfo(String info) throws IOException {
		if (info != null && info.length() > 0) {
			signature.set(SIGNATURE + info);
		}
		else {
			signature.set(SIGNATURE);
		}
	}

	/**
	 * Returns the last update time of the RRD.
	 *
	 * @return Timestamp (Unix epoch, no milliseconds) corresponding to the last update time.
	 * @throws IOException Thrown in case of I/O error
	 */
	public long getLastUpdateTime() throws IOException {
		return lastUpdateTime.get();
	}

	/**
	 * Returns primary RRD time step.
	 *
	 * @return Primary time step in seconds
	 * @throws IOException Thrown in case of I/O error
	 */
	public long getStep() throws IOException {
	    if (m_primitiveStep == null) {
	        m_primitiveStep = step.get();
	    }
	    return m_primitiveStep;
	}

	/**
	 * Returns the number of datasources defined in the RRD.
	 *
	 * @return Number of datasources defined
	 * @throws IOException Thrown in case of I/O error
	 */
	public int getDsCount() throws IOException {
	    if (m_primitiveDsCount == null) {
	        m_primitiveDsCount = dsCount.get();
	    }
	    return m_primitiveDsCount;
	}

	/**
	 * Returns the number of archives defined in the RRD.
	 *
	 * @return Number of archives defined
	 * @throws IOException Thrown in case of I/O error
	 */
	public int getArcCount() throws IOException {
	    if (m_primitiveArcCount == null) {
	        m_primitiveArcCount = arcCount.get();
	    }
	    return m_primitiveArcCount;
	}

	public void setLastUpdateTime(final long lastUpdateTime) throws IOException {
		this.lastUpdateTime.set(lastUpdateTime);
	}

	String dump() throws IOException {
		return "== HEADER ==\n" +
				"signature:" + getSignature() +
				" lastUpdateTime:" + getLastUpdateTime() +
				" step:" + getStep() +
				" dsCount:" + getDsCount() +
				" arcCount:" + getArcCount() + "\n";
	}

	void appendXml(XmlWriter writer) throws IOException {
		writer.writeComment(signature.get());
		writer.writeTag("version", RRDTOOL_VERSION);
		writer.writeComment("Seconds");
		writer.writeTag("step", step.get());
		writer.writeComment(Util.getDate(lastUpdateTime.get()));
		writer.writeTag("lastupdate", lastUpdateTime.get());
	}

	/**
	 * Copies object's internal state to another Header object.
	 *
	 * @param other New Header object to copy state to
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown if supplied argument is not a Header object
	 */
	public void copyStateTo(final RrdUpdater other) throws IOException, RrdException {
		if (!(other instanceof Header)) {
			throw new RrdException( "Cannot copy Header object to " + other.getClass().getName());
		}
		final Header header = (Header) other;
		header.signature.set(signature.get());
		header.lastUpdateTime.set(lastUpdateTime.get());
	}

	/**
	 * Returns the underlying storage (backend) object which actually performs all
	 * I/O operations.
	 *
	 * @return I/O backend object
	 */
	public RrdBackend getRrdBackend() {
		return parentDb.getRrdBackend();
	}

	boolean isJRobinHeader() throws IOException {
		return signature.get().startsWith(SIGNATURE);
	}

	void validateHeader() throws IOException, RrdException {
		if (!isJRobinHeader()) {
			throw new RrdException("Invalid file header. File [" + parentDb.getCanonicalPath() + "] is not a JRobin RRD file");
		}
	}

	/**
	 * Required to implement RrdUpdater interface. You should never call this method directly.
	 *
	 * @return Allocator object
	 */
	public RrdAllocator getRrdAllocator() {
		return parentDb.getRrdAllocator();
	}
}
