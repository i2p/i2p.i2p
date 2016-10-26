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
 * Class to represent archive values for a single datasource. Robin class is the heart of
 * the so-called "round robin database" concept. Basically, each Robin object is a
 * fixed length array of double values. Each double value reperesents consolidated, archived
 * value for the specific timestamp. When the underlying array of double values gets completely
 * filled, new values will replace the oldest ones.
 * <p>
 * Robin object does not hold values in memory - such object could be quite large.
 * Instead of it, Robin reads them from the backend I/O only when necessary.
 *
 * @author <a href="mailto:saxon@jrobin.org">Sasa Markovic</a>
 */
public class Robin implements RrdUpdater {
	private Archive parentArc;
	private RrdInt pointer;
	private RrdDoubleArray values;
	private int rows;

	Robin(Archive parentArc, int rows, boolean shouldInitialize) throws IOException {
		this.parentArc = parentArc;
		this.pointer = new RrdInt(this);
		this.values = new RrdDoubleArray(this, rows);
		this.rows = rows;
		if (shouldInitialize) {
			pointer.set(0);
			values.set(0, Double.NaN, rows);
		}
	}

	/**
	 * Fetches all archived values.
	 *
	 * @return Array of double archive values, starting from the oldest one.
	 * @throws IOException Thrown in case of I/O specific error.
	 */
	public double[] getValues() throws IOException {
		return getValues(0, rows);
	}

	// stores single value
	void store(double newValue) throws IOException {
		int position = pointer.get();
		values.set(position, newValue);
		pointer.set((position + 1) % rows);
	}

	// stores the same value several times
	void bulkStore(double newValue, int bulkCount) throws IOException {
		assert bulkCount <= rows: "Invalid number of bulk updates: " + bulkCount +
				" rows=" + rows;
		int position = pointer.get();
		// update tail
		int tailUpdateCount = Math.min(rows - position, bulkCount);
		values.set(position, newValue, tailUpdateCount);
		pointer.set((position + tailUpdateCount) % rows);
		// do we need to update from the start?
		int headUpdateCount = bulkCount - tailUpdateCount;
		if (headUpdateCount > 0) {
			values.set(0, newValue, headUpdateCount);
			pointer.set(headUpdateCount);
		}
	}

	void update(double[] newValues) throws IOException {
		assert rows == newValues.length: "Invalid number of robin values supplied (" + newValues.length +
				"), exactly " + rows + " needed";
		pointer.set(0);
		values.writeDouble(0, newValues);
	}

	/**
	 * Updates archived values in bulk.
	 *
	 * @param newValues Array of double values to be stored in the archive
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown if the length of the input array is different from the length of
	 *                      this archive
	 */
	public void setValues(double[] newValues) throws IOException, RrdException {
		if (rows != newValues.length) {
			throw new RrdException("Invalid number of robin values supplied (" + newValues.length +
					"), exactly " + rows + " needed");
		}
		update(newValues);
	}

	/**
	 * (Re)sets all values in this archive to the same value.
	 *
	 * @param newValue New value
	 * @throws IOException Thrown in case of I/O error
	 */
	public void setValues(double newValue) throws IOException {
		double[] values = new double[rows];
		for (int i = 0; i < values.length; i++) {
			values[i] = newValue;
		}
		update(values);
	}

	String dump() throws IOException {
		StringBuffer buffer = new StringBuffer("Robin " + pointer.get() + "/" + rows + ": ");
		double[] values = getValues();
		for (double value : values) {
			buffer.append(Util.formatDouble(value, true)).append(" ");
		}
		buffer.append("\n");
		return buffer.toString();
	}

	/**
	 * Returns the i-th value from the Robin archive.
	 *
	 * @param index Value index
	 * @return Value stored in the i-th position (the oldest value has zero index)
	 * @throws IOException Thrown in case of I/O specific error.
	 */
	public double getValue(int index) throws IOException {
		int arrayIndex = (pointer.get() + index) % rows;
		return values.get(arrayIndex);
	}

	/**
	 * Sets the i-th value in the Robin archive.
	 *
	 * @param index index in the archive (the oldest value has zero index)
	 * @param value value to be stored
	 * @throws IOException Thrown in case of I/O specific error.
	 */
	public void setValue(int index, double value) throws IOException {
		int arrayIndex = (pointer.get() + index) % rows;
		values.set(arrayIndex, value);
	}

	double[] getValues(int index, int count) throws IOException {
		assert count <= rows: "Too many values requested: " + count + " rows=" + rows;
		int startIndex = (pointer.get() + index) % rows;
		int tailReadCount = Math.min(rows - startIndex, count);
		double[] tailValues = values.get(startIndex, tailReadCount);
		if (tailReadCount < count) {
			int headReadCount = count - tailReadCount;
			double[] headValues = values.get(0, headReadCount);
			double[] values = new double[count];
			int k = 0;
			for (double tailValue : tailValues) {
				values[k++] = tailValue;
			}
			for (double headValue : headValues) {
				values[k++] = headValue;
			}
			return values;
		}
		else {
			return tailValues;
		}
	}

	/**
	 * Returns the Archive object to which this Robin object belongs.
	 *
	 * @return Parent Archive object
	 */
	public Archive getParent() {
		return parentArc;
	}

	/**
	 * Returns the size of the underlying array of archived values.
	 *
	 * @return Number of stored values
	 */
	public int getSize() {
		return rows;
	}

	/**
	 * Copies object's internal state to another Robin object.
	 *
	 * @param other New Robin object to copy state to
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown if supplied argument is not a Robin object
	 */
	public void copyStateTo(RrdUpdater other) throws IOException, RrdException {
		if (!(other instanceof Robin)) {
			throw new RrdException(
					"Cannot copy Robin object to " + other.getClass().getName());
		}
		Robin robin = (Robin) other;
		int rowsDiff = rows - robin.rows;
		if (rowsDiff == 0) {
			// Identical dimensions. Do copy in BULK to speed things up
			robin.pointer.set(pointer.get());
			robin.values.writeBytes(values.readBytes());
		}
		else {
			// different sizes
			for (int i = 0; i < robin.rows; i++) {
				int j = i + rowsDiff;
				robin.store(j >= 0 ? getValue(j) : Double.NaN);
			}
		}
	}

	/**
	 * Filters values stored in this archive based on the given boundary.
	 * Archived values found to be outside of <code>[minValue, maxValue]</code> interval (inclusive)
	 * will be silently replaced with <code>NaN</code>.
	 *
	 * @param minValue lower boundary
	 * @param maxValue upper boundary
	 * @throws IOException Thrown in case of I/O error
	 */
	public void filterValues(double minValue, double maxValue) throws IOException {
		for (int i = 0; i < rows; i++) {
			double value = values.get(i);
			if (!Double.isNaN(minValue) && !Double.isNaN(value) && minValue > value) {
				values.set(i, Double.NaN);
			}
			if (!Double.isNaN(maxValue) && !Double.isNaN(value) && maxValue < value) {
				values.set(i, Double.NaN);
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
		return parentArc.getRrdBackend();
	}

	/**
	 * Required to implement RrdUpdater interface. You should never call this method directly.
	 *
	 * @return Allocator object
	 */
	public RrdAllocator getRrdAllocator() {
		return parentArc.getRrdAllocator();
	}
}
