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
 * Class to represent internal RRD archive state for a single datasource. Objects of this
 * class are never manipulated directly, it's up to JRobin framework to manage
 * internal arcihve states.<p>
 *
 * @author <a href="mailto:saxon@jrobin.org">Sasa Markovic</a>
 */
public class ArcState implements RrdUpdater {
	private Archive parentArc;

	private RrdDouble accumValue;
	private RrdLong nanSteps;

	ArcState(Archive parentArc, boolean shouldInitialize) throws IOException {
		this.parentArc = parentArc;
		accumValue = new RrdDouble(this);
		nanSteps = new RrdLong(this);
		if (shouldInitialize) {
			Header header = parentArc.getParentDb().getHeader();
			long step = header.getStep();
			long lastUpdateTime = header.getLastUpdateTime();
			long arcStep = parentArc.getArcStep();
			long initNanSteps = (Util.normalize(lastUpdateTime, step) -
					Util.normalize(lastUpdateTime, arcStep)) / step;
			accumValue.set(Double.NaN);
			nanSteps.set(initNanSteps);
		}
	}

	String dump() throws IOException {
		return "accumValue:" + accumValue.get() + " nanSteps:" + nanSteps.get() + "\n";
	}

	void setNanSteps(long value) throws IOException {
		nanSteps.set(value);
	}

	/**
	 * Returns the number of currently accumulated NaN steps.
	 *
	 * @return Number of currently accumulated NaN steps.
	 * @throws IOException Thrown in case of I/O error
	 */
	public long getNanSteps() throws IOException {
		return nanSteps.get();
	}

	void setAccumValue(double value) throws IOException {
		accumValue.set(value);
	}

	/**
	 * Returns the value accumulated so far.
	 *
	 * @return Accumulated value
	 * @throws IOException Thrown in case of I/O error
	 */
	public double getAccumValue() throws IOException {
		return accumValue.get();
	}

	/**
	 * Returns the Archive object to which this ArcState object belongs.
	 *
	 * @return Parent Archive object.
	 */
	public Archive getParent() {
		return parentArc;
	}

	void appendXml(XmlWriter writer) throws IOException {
		writer.startTag("ds");
		writer.writeTag("value", accumValue.get());
		writer.writeTag("unknown_datapoints", nanSteps.get());
		writer.closeTag(); // ds
	}

	/**
	 * Copies object's internal state to another ArcState object.
	 *
	 * @param other New ArcState object to copy state to
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown if supplied argument is not an ArcState object
	 */
	public void copyStateTo(RrdUpdater other) throws IOException, RrdException {
		if (!(other instanceof ArcState)) {
			throw new RrdException(
					"Cannot copy ArcState object to " + other.getClass().getName());
		}
		ArcState arcState = (ArcState) other;
		arcState.accumValue.set(accumValue.get());
		arcState.nanSteps.set(nanSteps.get());
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

    public String toString() {
        return "ArcState@" + Integer.toHexString(hashCode()) + "[parentArc=" + parentArc + ",accumValue=" + accumValue + ",nanSteps=" + nanSteps + "]";
    }
}
