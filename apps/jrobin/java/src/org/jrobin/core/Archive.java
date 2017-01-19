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
 * Class to represent single RRD archive in a RRD with its internal state.
 * Normally, you don't need methods to manipulate archive objects directly
 * because JRobin framework does it automatically for you.
 * <p>
 * Each archive object consists of three parts: archive definition, archive state objects
 * (one state object for each datasource) and round robin archives (one round robin for
 * each datasource). API (read-only) is provided to access each of theese parts.
 *
 * @author <a href="mailto:saxon@jrobin.org">Sasa Markovic</a>
 */
public class Archive implements RrdUpdater, ConsolFuns {
	private RrdDb parentDb;
	// definition
	private RrdString consolFun;
	private RrdDouble xff;
	private RrdInt steps, rows;
	// state
	private Robin[] robins;
	private ArcState[] states;

	Archive(final RrdDb parentDb, final ArcDef arcDef) throws IOException {
	    final boolean shouldInitialize = arcDef != null;
		this.parentDb = parentDb;
		consolFun = new RrdString(this, true);  // constant, may be cached
		xff = new RrdDouble(this);
		steps = new RrdInt(this, true);			// constant, may be cached
		rows = new RrdInt(this, true);			// constant, may be cached
		if (shouldInitialize) {
			consolFun.set(arcDef.getConsolFun());
			xff.set(arcDef.getXff());
			steps.set(arcDef.getSteps());
			rows.set(arcDef.getRows());
		}
		final int dsCount = parentDb.getHeader().getDsCount();
		states = new ArcState[dsCount];
		robins = new Robin[dsCount];
		final int numRows = rows.get();
		for (int i = 0; i < dsCount; i++) {
			states[i] = new ArcState(this, shouldInitialize);
			robins[i] = new Robin(this, numRows, shouldInitialize);
		}
	}

	// read from XML
	Archive(final RrdDb parentDb, final DataImporter reader, final int arcIndex) throws IOException, RrdException,RrdException {
		this(parentDb, new ArcDef(
				reader.getConsolFun(arcIndex), reader.getXff(arcIndex),
				reader.getSteps(arcIndex), reader.getRows(arcIndex)));
		final int dsCount = parentDb.getHeader().getDsCount();
		for (int i = 0; i < dsCount; i++) {
			// restore state
			states[i].setAccumValue(reader.getStateAccumValue(arcIndex, i));
			states[i].setNanSteps(reader.getStateNanSteps(arcIndex, i));
			// restore robins
			double[] values = reader.getValues(arcIndex, i);
			robins[i].update(values);
		}
	}

	/**
	 * Returns archive time step in seconds. Archive step is equal to RRD step
	 * multiplied with the number of archive steps.
	 *
	 * @return Archive time step in seconds
	 * @throws IOException Thrown in case of I/O error.
	 */
	public long getArcStep() throws IOException {
	    final long step = parentDb.getHeader().getStep();
		return step * steps.get();
	}

	String dump() throws IOException {
	    final StringBuffer buffer = new StringBuffer("== ARCHIVE ==\n");
		buffer.append("RRA:").append(consolFun.get()).append(":").append(xff.get()).append(":").append(steps.get()).
				append(":").append(rows.get()).append("\n");
		buffer.append("interval [").append(getStartTime()).append(", ").append(getEndTime()).append("]" + "\n");
		for (int i = 0; i < robins.length; i++) {
			buffer.append(states[i].dump());
			buffer.append(robins[i].dump());
		}
		return buffer.toString();
	}

	RrdDb getParentDb() {
		return parentDb;
	}

	public void archive(final int dsIndex, final double value, final long numStepUpdates) throws IOException {
	    final Robin robin = robins[dsIndex];
		final ArcState state = states[dsIndex];
		final long step = parentDb.getHeader().getStep();
		final long lastUpdateTime = parentDb.getHeader().getLastUpdateTime();
		long updateTime = Util.normalize(lastUpdateTime, step) + step;
		final long arcStep = getArcStep();
        final String consolFunString = consolFun.get();
        final int numSteps = steps.get();
        final int numRows = rows.get();
        final double xffValue = xff.get();

        // finish current step
		long numUpdates = numStepUpdates;
		while (numUpdates > 0) {
			accumulate(state, value, consolFunString);
			numUpdates--;
			if (updateTime % arcStep == 0) {
                finalizeStep(state, robin, consolFunString, numSteps, xffValue);
				break;
			}
			else {
				updateTime += step;
			}
		}
		// update robin in bulk
		final int bulkUpdateCount = (int) Math.min(numUpdates / numSteps, (long) numRows);
		robin.bulkStore(value, bulkUpdateCount);
		// update remaining steps
		final long remainingUpdates = numUpdates % numSteps;
		for (long i = 0; i < remainingUpdates; i++) {
			accumulate(state, value, consolFunString);
		}
	}

	private void accumulate(final ArcState state, final double value, String consolFunString) throws IOException {
		if (Double.isNaN(value)) {
			state.setNanSteps(state.getNanSteps() + 1);
		}
		else {
            final double accumValue = state.getAccumValue();
            if (consolFunString.equals(CF_MIN)) {
				final double minValue = Util.min(accumValue, value);
				if (minValue != accumValue) {
				    state.setAccumValue(minValue);
				}
			}
			else if (consolFunString.equals(CF_MAX)) {
				final double maxValue = Util.max(accumValue, value);
				if (maxValue != accumValue) {
				    state.setAccumValue(maxValue);
				}
			}
			else if (consolFunString.equals(CF_LAST)) {
				state.setAccumValue(value);
			}
			else if (consolFunString.equals(CF_AVERAGE)) {
				state.setAccumValue(Util.sum(accumValue, value));
			}
		}
	}

	private void finalizeStep(final ArcState state, final Robin robin, final String consolFunString, final long numSteps, final double xffValue) throws IOException {
	    final long nanSteps = state.getNanSteps();
		//double nanPct = (double) nanSteps / (double) arcSteps;
		double accumValue = state.getAccumValue();
		if (nanSteps <= xffValue * numSteps && !Double.isNaN(accumValue)) {
			if (consolFunString.equals(CF_AVERAGE)) {
				accumValue /= (numSteps - nanSteps);
			}
			robin.store(accumValue);
		} else {
		    robin.store(Double.NaN);
		}
		state.setAccumValue(Double.NaN);
		state.setNanSteps(0);
	}

	/**
	 * Returns archive consolidation function ("AVERAGE", "MIN", "MAX" or "LAST").
	 *
	 * @return Archive consolidation function.
	 * @throws IOException Thrown in case of I/O error.
	 */
	public String getConsolFun() throws IOException {
		return consolFun.get();
	}

	/**
	 * Returns archive X-files factor.
	 *
	 * @return Archive X-files factor (between 0 and 1).
	 * @throws IOException Thrown in case of I/O error.
	 */
	public double getXff() throws IOException {
		return xff.get();
	}

	/**
	 * Returns the number of archive steps.
	 *
	 * @return Number of archive steps.
	 * @throws IOException Thrown in case of I/O error.
	 */
	public int getSteps() throws IOException {
		return steps.get();
	}

	/**
	 * Returns the number of archive rows.
	 *
	 * @return Number of archive rows.
	 * @throws IOException Thrown in case of I/O error.
	 */
	public int getRows() throws IOException {
		return rows.get();
	}

	/**
	 * Returns current starting timestamp. This value is not constant.
	 *
	 * @return Timestamp corresponding to the first archive row
	 * @throws IOException Thrown in case of I/O error.
	 */
	public long getStartTime() throws IOException {
	    final long endTime = getEndTime();
		final long arcStep = getArcStep();
		final long numRows = rows.get();
		return endTime - (numRows - 1) * arcStep;
	}

	/**
	 * Returns current ending timestamp. This value is not constant.
	 *
	 * @return Timestamp corresponding to the last archive row
	 * @throws IOException Thrown in case of I/O error.
	 */
	public long getEndTime() throws IOException {
		final long arcStep = getArcStep();
		final long lastUpdateTime = parentDb.getHeader().getLastUpdateTime();
		return Util.normalize(lastUpdateTime, arcStep);
	}

	/**
	 * Returns the underlying archive state object. Each datasource has its
	 * corresponding ArcState object (archive states are managed independently
	 * for each RRD datasource).
	 *
	 * @param dsIndex Datasource index
	 * @return Underlying archive state object
	 */
	public ArcState getArcState(final int dsIndex) {
		return states[dsIndex];
	}

	/**
	 * Returns the underlying round robin archive. Robins are used to store actual
	 * archive values on a per-datasource basis.
	 *
	 * @param dsIndex Index of the datasource in the RRD.
	 * @return Underlying round robin archive for the given datasource.
	 */
	public Robin getRobin(final int dsIndex) {
		return robins[dsIndex];
	}

	FetchData fetchData(final FetchRequest request) throws IOException, RrdException {
	    final long arcStep = getArcStep();
		final long fetchStart = Util.normalize(request.getFetchStart(), arcStep);
		long fetchEnd = Util.normalize(request.getFetchEnd(), arcStep);
		if (fetchEnd < request.getFetchEnd()) {
			fetchEnd += arcStep;
		}
		final long startTime = getStartTime();
		final long endTime = getEndTime();
		String[] dsToFetch = request.getFilter();
		if (dsToFetch == null) {
			dsToFetch = parentDb.getDsNames();
		}
		final int dsCount = dsToFetch.length;
		final int ptsCount = (int) ((fetchEnd - fetchStart) / arcStep + 1);
		final long[] timestamps = new long[ptsCount];
		final double[][] values = new double[dsCount][ptsCount];
		final long matchStartTime = Math.max(fetchStart, startTime);
		final long matchEndTime = Math.min(fetchEnd, endTime);
		double[][] robinValues = null;
		if (matchStartTime <= matchEndTime) {
			// preload robin values
		    final int matchCount = (int) ((matchEndTime - matchStartTime) / arcStep + 1);
			final int matchStartIndex = (int) ((matchStartTime - startTime) / arcStep);
			robinValues = new double[dsCount][];
			for (int i = 0; i < dsCount; i++) {
			    final int dsIndex = parentDb.getDsIndex(dsToFetch[i]);
				robinValues[i] = robins[dsIndex].getValues(matchStartIndex, matchCount);
			}
		}
		for (int ptIndex = 0; ptIndex < ptsCount; ptIndex++) {
		    final long time = fetchStart + ptIndex * arcStep;
		    timestamps[ptIndex] = time;
			for (int i = 0; i < dsCount; i++) {
				double value = Double.NaN;
				if (time >= matchStartTime && time <= matchEndTime) {
					// inbound time
					final int robinValueIndex = (int) ((time - matchStartTime) / arcStep);
					assert robinValues != null;
					value = robinValues[i][robinValueIndex];
				}
				values[i][ptIndex] = value;
			}
		}
		final FetchData fetchData = new FetchData(this, request);
		fetchData.setTimestamps(timestamps);
		fetchData.setValues(values);
		return fetchData;
	}

	void appendXml(final XmlWriter writer) throws IOException {
		writer.startTag("rra");
		writer.writeTag("cf", consolFun.get());
		writer.writeComment(getArcStep() + " seconds");
		writer.writeTag("pdp_per_row", steps.get());
		writer.writeTag("xff", xff.get());
		writer.startTag("cdp_prep");
		for (final ArcState state : states) {
			state.appendXml(writer);
		}
		writer.closeTag(); // cdp_prep
		writer.startTag("database");
		final long startTime = getStartTime();
		for (int i = 0; i < rows.get(); i++) {
			final long time = startTime + i * getArcStep();
			writer.writeComment(Util.getDate(time) + " / " + time);
			writer.startTag("row");
			for (final Robin robin : robins) {
				writer.writeTag("v", robin.getValue(i));
			}
			writer.closeTag(); // row
		}
		writer.closeTag(); // database
		writer.closeTag(); // rra
	}

	/**
	 * Copies object's internal state to another Archive object.
	 *
	 * @param other New Archive object to copy state to
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown if supplied argument is not an Archive object
	 */
	public void copyStateTo(final RrdUpdater other) throws IOException, RrdException {
		if (!(other instanceof Archive)) {
			throw new RrdException("Cannot copy Archive object to " + other.getClass().getName());
		}
		final Archive arc = (Archive) other;
		if (!arc.consolFun.get().equals(consolFun.get())) {
			throw new RrdException("Incompatible consolidation functions");
		}
		if (arc.steps.get() != steps.get()) {
			throw new RrdException("Incompatible number of steps");
		}
		final int count = parentDb.getHeader().getDsCount();
		for (int i = 0; i < count; i++) {
		    final int j = Util.getMatchingDatasourceIndex(parentDb, i, arc.parentDb);
			if (j >= 0) {
				states[i].copyStateTo(arc.states[j]);
				robins[i].copyStateTo(arc.robins[j]);
			}
		}
	}

	/**
	 * Sets X-files factor to a new value.
	 *
	 * @param xff New X-files factor value. Must be &gt;= 0 and &lt; 1.
	 * @throws RrdException Thrown if invalid value is supplied
	 * @throws IOException  Thrown in case of I/O error
	 */
	public void setXff(final double xff) throws RrdException, IOException {
		if (xff < 0D || xff >= 1D) {
			throw new RrdException("Invalid xff supplied (" + xff + "), must be >= 0 and < 1");
		}
		this.xff.set(xff);
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
        return "Archive@" + Integer.toHexString(hashCode()) + "[parentDb=" + parentDb + ",consolFun=" + consolFun + ",xff=" + xff + ",steps=" + steps + ",rows=" + rows + ",robins=" + robins + ",states=" + states + "]";
    }
}
