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
 * Class to represent single datasource within RRD. Each datasource object holds the
 * following information: datasource definition (once set, never changed) and
 * datasource state variables (changed whenever RRD gets updated).
 * <p>
 * Normally, you don't need to manipluate Datasource objects directly, it's up to
 * JRobin framework to do it for you.
 *
 * @author <a href="mailto:saxon@jrobin.org">Sasa Markovic</a>
 */

public class Datasource implements RrdUpdater, DsTypes {
	private static final double MAX_32_BIT = Math.pow(2, 32);
	private static final double MAX_64_BIT = Math.pow(2, 64);

	private RrdDb parentDb;
	// definition
	private RrdString dsName, dsType;
	private RrdLong heartbeat;
	private RrdDouble minValue, maxValue;

	// cache
	private String m_primitiveDsName = null;
	private String m_primitiveDsType = null;

	// state variables
	private RrdDouble lastValue;
	private RrdLong nanSeconds;
	private RrdDouble accumValue;

	Datasource(final RrdDb parentDb, final DsDef dsDef) throws IOException {
		boolean shouldInitialize = dsDef != null;
		this.parentDb = parentDb;
		dsName = new RrdString(this);
		dsType = new RrdString(this);
		heartbeat = new RrdLong(this);
		minValue = new RrdDouble(this);
		maxValue = new RrdDouble(this);
		lastValue = new RrdDouble(this);
		accumValue = new RrdDouble(this);
		nanSeconds = new RrdLong(this);
		if (shouldInitialize) {
		    dsName.set(dsDef.getDsName());
            m_primitiveDsName = null;
			dsType.set(dsDef.getDsType());
			m_primitiveDsType = null;
			heartbeat.set(dsDef.getHeartbeat());
			minValue.set(dsDef.getMinValue());
			maxValue.set(dsDef.getMaxValue());
			lastValue.set(Double.NaN);
			accumValue.set(0.0);
			final Header header = parentDb.getHeader();
			nanSeconds.set(header.getLastUpdateTime() % header.getStep());
		}
	}

	Datasource(final RrdDb parentDb, final DataImporter reader, final int dsIndex) throws IOException, RrdException {
		this(parentDb, null);
		dsName.set(reader.getDsName(dsIndex));
		m_primitiveDsName = null;
		dsType.set(reader.getDsType(dsIndex));
		m_primitiveDsType = null;
		heartbeat.set(reader.getHeartbeat(dsIndex));
		minValue.set(reader.getMinValue(dsIndex));
		maxValue.set(reader.getMaxValue(dsIndex));
		lastValue.set(reader.getLastValue(dsIndex));
		accumValue.set(reader.getAccumValue(dsIndex));
		nanSeconds.set(reader.getNanSeconds(dsIndex));
	}

	String dump() throws IOException {
		return "== DATASOURCE ==\n" +
				"DS:" + dsName.get() + ":" + dsType.get() + ":" +
				heartbeat.get() + ":" + minValue.get() + ":" +
				maxValue.get() + "\nlastValue:" + lastValue.get() +
				" nanSeconds:" + nanSeconds.get() +
				" accumValue:" + accumValue.get() + "\n";
	}

	/**
	 * Returns datasource name.
	 *
	 * @return Datasource name
	 * @throws IOException Thrown in case of I/O error
	 */
	public String getDsName() throws IOException {
	    if (m_primitiveDsName == null) {
	        m_primitiveDsName = dsName.get();
	    }
	    return m_primitiveDsName;
	}

	/**
	 * Returns datasource type (GAUGE, COUNTER, DERIVE, ABSOLUTE).
	 *
	 * @return Datasource type.
	 * @throws IOException Thrown in case of I/O error
	 */
	public String getDsType() throws IOException {
	    if (m_primitiveDsType == null) {
	        m_primitiveDsType = dsType.get();
	    }
	    return m_primitiveDsType;
	}

	/**
	 * Returns datasource heartbeat
	 *
	 * @return Datasource heartbeat
	 * @throws IOException Thrown in case of I/O error
	 */

	public long getHeartbeat() throws IOException {
		return heartbeat.get();
	}

	/**
	 * Returns mimimal allowed value for this datasource.
	 *
	 * @return Minimal value allowed.
	 * @throws IOException Thrown in case of I/O error
	 */
	public double getMinValue() throws IOException {
		return minValue.get();
	}

	/**
	 * Returns maximal allowed value for this datasource.
	 *
	 * @return Maximal value allowed.
	 * @throws IOException Thrown in case of I/O error
	 */
	public double getMaxValue() throws IOException {
		return maxValue.get();
	}

	/**
	 * Returns last known value of the datasource.
	 *
	 * @return Last datasource value.
	 * @throws IOException Thrown in case of I/O error
	 */
	public double getLastValue() throws IOException {
		return lastValue.get();
	}

	/**
	 * Returns value this datasource accumulated so far.
	 *
	 * @return Accumulated datasource value.
	 * @throws IOException Thrown in case of I/O error
	 */
	public double getAccumValue() throws IOException {
		return accumValue.get();
	}

	/**
	 * Returns the number of accumulated NaN seconds.
	 *
	 * @return Accumulated NaN seconds.
	 * @throws IOException Thrown in case of I/O error
	 */
	public long getNanSeconds() throws IOException {
		return nanSeconds.get();
	}

	void process(final long newTime, final double newValue) throws IOException, RrdException {
	    final Header header = parentDb.getHeader();
	    final long step = header.getStep();
		final long oldTime = header.getLastUpdateTime();
		final long startTime = Util.normalize(oldTime, step);
		final long endTime = startTime + step;
		final double oldValue = lastValue.get();
		final double updateValue = calculateUpdateValue(oldTime, oldValue, newTime, newValue);
		if (newTime < endTime) {
			accumulate(oldTime, newTime, updateValue);
		}
		else {
			// should store something
			final long boundaryTime = Util.normalize(newTime, step);
			accumulate(oldTime, boundaryTime, updateValue);
			final double value = calculateTotal(startTime, boundaryTime);
			// how many updates?
			final long numSteps = (boundaryTime - endTime) / step + 1L;
			// ACTION!
			parentDb.archive(this, value, numSteps);
			// cleanup
			nanSeconds.set(0);
			accumValue.set(0.0);
			accumulate(boundaryTime, newTime, updateValue);
		}
	}

	private double calculateUpdateValue(final long oldTime, final double oldValue, final long newTime, final double newValue) throws IOException {
		double updateValue = Double.NaN;
		if (newTime - oldTime <= heartbeat.get()) {
		    final String type = dsType.get();
			if (type.equals(DT_GAUGE)) {
				updateValue = newValue;
			}
			else if (type.equals(DT_ABSOLUTE)) {
				if (!Double.isNaN(newValue)) {
					updateValue = newValue / (newTime - oldTime);
				}
			}
			else if (type.equals(DT_DERIVE)) {
				if (!Double.isNaN(newValue) && !Double.isNaN(oldValue)) {
					updateValue = (newValue - oldValue) / (newTime - oldTime);
				}
			}
			else if (type.equals(DT_COUNTER)) {
				if (!Double.isNaN(newValue) && !Double.isNaN(oldValue)) {
				    double diff = newValue - oldValue;
					if (diff < 0) {
						diff += MAX_32_BIT;
					}
					if (diff < 0) {
						diff += MAX_64_BIT - MAX_32_BIT;
					}
					if (diff >= 0) {
						updateValue = diff / (newTime - oldTime);
					}
				}
			}
			if (!Double.isNaN(updateValue)) {
			    final double minVal = minValue.get();
				final double maxVal = maxValue.get();
				if (!Double.isNaN(minVal) && updateValue < minVal) {
					updateValue = Double.NaN;
				}
				if (!Double.isNaN(maxVal) && updateValue > maxVal) {
					updateValue = Double.NaN;
				}
			}
		}
		lastValue.set(newValue);
		return updateValue;
	}

	private void accumulate(final long oldTime, final long newTime, final double updateValue) throws IOException {
		if (Double.isNaN(updateValue)) {
			nanSeconds.set(nanSeconds.get() + (newTime - oldTime));
		}
		else {
			accumValue.set(accumValue.get() + updateValue * (newTime - oldTime));
		}
	}

	private double calculateTotal(final long startTime, final long boundaryTime) throws IOException {
		double totalValue = Double.NaN;
		final long validSeconds = boundaryTime - startTime - nanSeconds.get();
		if (nanSeconds.get() <= heartbeat.get() && validSeconds > 0) {
			totalValue = accumValue.get() / validSeconds;
		}
		// IMPORTANT:
		// if datasource name ends with "!", we'll send zeros instead of NaNs
		// this might be handy from time to time
		if (Double.isNaN(totalValue) && dsName.get().endsWith(DsDef.FORCE_ZEROS_FOR_NANS_SUFFIX)) {
			totalValue = 0D;
		}
		return totalValue;
	}

	void appendXml(final XmlWriter writer) throws IOException {
		writer.startTag("ds");
		writer.writeTag("name", dsName.get());
		writer.writeTag("type", dsType.get());
		writer.writeTag("minimal_heartbeat", heartbeat.get());
		writer.writeTag("min", minValue.get());
		writer.writeTag("max", maxValue.get());
		writer.writeComment("PDP Status");
		writer.writeTag("last_ds", lastValue.get(), "UNKN");
		writer.writeTag("value", accumValue.get());
		writer.writeTag("unknown_sec", nanSeconds.get());
		writer.closeTag();  // ds
	}

	/**
	 * Copies object's internal state to another Datasource object.
	 *
	 * @param other New Datasource object to copy state to
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown if supplied argument is not a Datasource object
	 */
	public void copyStateTo(final RrdUpdater other) throws IOException, RrdException {
		if (!(other instanceof Datasource)) {
			throw new RrdException("Cannot copy Datasource object to " + other.getClass().getName());
		}
		final Datasource datasource = (Datasource) other;
		if (!datasource.dsName.get().equals(dsName.get())) {
			throw new RrdException("Incomaptible datasource names");
		}
		if (!datasource.dsType.get().equals(dsType.get())) {
			throw new RrdException("Incomaptible datasource types");
		}
		datasource.lastValue.set(lastValue.get());
		datasource.nanSeconds.set(nanSeconds.get());
		datasource.accumValue.set(accumValue.get());
	}

	/**
	 * Returns index of this Datasource object in the RRD.
	 *
	 * @return Datasource index in the RRD.
	 * @throws IOException Thrown in case of I/O error
	 */
	public int getDsIndex() throws IOException {
		try {
			return parentDb.getDsIndex(dsName.get());
		}
		catch (final RrdException e) {
			return -1;
		}
	}

	/**
	 * Sets datasource heartbeat to a new value.
	 *
	 * @param heartbeat New heartbeat value
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown if invalid (non-positive) heartbeat value is specified.
	 */
	public void setHeartbeat(final long heartbeat) throws RrdException, IOException {
		if (heartbeat < 1L) {
			throw new RrdException("Invalid heartbeat specified: " + heartbeat);
		}
		this.heartbeat.set(heartbeat);
	}

	/**
	 * Sets datasource name to a new value
	 *
	 * @param newDsName New datasource name
	 * @throws RrdException Thrown if invalid data source name is specified (name too long, or
	 *                      name already defined in the RRD
	 * @throws IOException  Thrown in case of I/O error
	 */
	public void setDsName(final String newDsName) throws RrdException, IOException {
		if (newDsName.length() > RrdString.STRING_LENGTH) {
			throw new RrdException("Invalid datasource name specified: " + newDsName);
		}
		if (parentDb.containsDs(newDsName)) {
			throw new RrdException("Datasource already defined in this RRD: " + newDsName);
		}
		dsName.set(newDsName);
		m_primitiveDsName = null;
	}

	public void setDsType(final String newDsType) throws RrdException, IOException {
		if (!DsDef.isValidDsType(newDsType)) {
			throw new RrdException("Invalid datasource type: " + newDsType);
		}
		// set datasource type
		this.dsType.set(newDsType);
		m_primitiveDsType = null;
		// reset datasource status
		lastValue.set(Double.NaN);
		accumValue.set(0.0);
		// reset archive status
		final int dsIndex = parentDb.getDsIndex(dsName.get());
		final Archive[] archives = parentDb.getArchives();
		for (final Archive archive : archives) {
			archive.getArcState(dsIndex).setAccumValue(Double.NaN);
		}
	}

	/**
	 * Sets minimum allowed value for this datasource. If <code>filterArchivedValues</code>
	 * argment is set to true, all archived values less then <code>minValue</code> will
	 * be fixed to NaN.
	 *
	 * @param minValue			 New minimal value. Specify <code>Double.NaN</code> if no minimal
	 *                             value should be set
	 * @param filterArchivedValues true, if archived datasource values should be fixed;
	 *                             false, otherwise.
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown if invalid minValue was supplied (not less then maxValue)
	 */
	public void setMinValue(final double minValue, final boolean filterArchivedValues) throws IOException, RrdException {
	    final double maxValue = this.maxValue.get();
		if (!Double.isNaN(minValue) && !Double.isNaN(maxValue) && minValue >= maxValue) {
			throw new RrdException("Invalid min/max values: " + minValue + "/" + maxValue);
		}
		this.minValue.set(minValue);
		if (!Double.isNaN(minValue) && filterArchivedValues) {
			final int dsIndex = getDsIndex();
			final Archive[] archives = parentDb.getArchives();
			for (final Archive archive : archives) {
				archive.getRobin(dsIndex).filterValues(minValue, Double.NaN);
			}
		}
	}

	/**
	 * Sets maximum allowed value for this datasource. If <code>filterArchivedValues</code>
	 * argment is set to true, all archived values greater then <code>maxValue</code> will
	 * be fixed to NaN.
	 *
	 * @param maxValue			 New maximal value. Specify <code>Double.NaN</code> if no max
	 *                             value should be set.
	 * @param filterArchivedValues true, if archived datasource values should be fixed;
	 *                             false, otherwise.
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown if invalid maxValue was supplied (not greater then minValue)
	 */
	public void setMaxValue(final double maxValue, final boolean filterArchivedValues) throws IOException, RrdException {
	    final double minValue = this.minValue.get();
		if (!Double.isNaN(minValue) && !Double.isNaN(maxValue) && minValue >= maxValue) {
			throw new RrdException("Invalid min/max values: " + minValue + "/" + maxValue);
		}
		this.maxValue.set(maxValue);
		if (!Double.isNaN(maxValue) && filterArchivedValues) {
			final int dsIndex = getDsIndex();
			final Archive[] archives = parentDb.getArchives();
			for (final Archive archive : archives) {
				archive.getRobin(dsIndex).filterValues(Double.NaN, maxValue);
			}
		}
	}

	/**
	 * Sets min/max values allowed for this datasource. If <code>filterArchivedValues</code>
	 * argment is set to true, all archived values less then <code>minValue</code> or
	 * greater then <code>maxValue</code> will be fixed to NaN.
	 *
	 * @param minValue			 New minimal value. Specify <code>Double.NaN</code> if no min
	 *                             value should be set.
	 * @param maxValue			 New maximal value. Specify <code>Double.NaN</code> if no max
	 *                             value should be set.
	 * @param filterArchivedValues true, if archived datasource values should be fixed;
	 *                             false, otherwise.
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown if invalid min/max values were supplied
	 */
	public void setMinMaxValue(final double minValue, final double maxValue, final boolean filterArchivedValues) throws IOException, RrdException {
		if (!Double.isNaN(minValue) && !Double.isNaN(maxValue) && minValue >= maxValue) {
			throw new RrdException("Invalid min/max values: " + minValue + "/" + maxValue);
		}
		this.minValue.set(minValue);
		this.maxValue.set(maxValue);
		if (!(Double.isNaN(minValue) && Double.isNaN(maxValue)) && filterArchivedValues) {
		    final int dsIndex = getDsIndex();
			final Archive[] archives = parentDb.getArchives();
			for (final Archive archive : archives) {
				archive.getRobin(dsIndex).filterValues(minValue, maxValue);
			}
		}
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

	/**
	 * Required to implement RrdUpdater interface. You should never call this method directly.
	 *
	 * @return Allocator object
	 */
	public RrdAllocator getRrdAllocator() {
		return parentDb.getRrdAllocator();
	}

	public String toString() {
	    return getClass().getName() + "@" + Integer.toHexString(hashCode()) + "[parentDb=" + parentDb
	        + ",dsName=" + dsName + ",dsType=" + dsType + ",heartbeat=" + heartbeat
	        + ",minValue=" + minValue + ",maxValue=" + maxValue + "]";
	}
}
