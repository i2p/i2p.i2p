/*******************************************************************************
 * Copyright (c) 2011 Craig Miskell
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
package org.jrobin.data;

import org.jrobin.core.RrdException;

public class PercentileDef extends Source {

    private Source m_source;

    private double m_value;

    private double m_percentile;

    @SuppressWarnings("unused")
	private boolean m_ignorenan;

    PercentileDef(String name, Source source, double percentile) {
        this(name, source, percentile, false);
    }

    PercentileDef(String name, Source source, double percentile, boolean ignorenan) {
        super(name);

        m_percentile = percentile;
        m_ignorenan = ignorenan;
        m_source = source;

        //The best we can do at this point; until this object has it's value realized over a
        // particular time period (with calculate()), there's not much else to do
        this.setValue(Double.NaN);
    }

    /**
     * Realize the calculation of this definition, over the given time period
     *
     * @param tStart the time period start
     * @param tEnd the time period end
     * @throws RrdException Thrown if we cannot get a percentile value for the time period.
     */
    public void calculate(long tStart, long tEnd) throws RrdException {
        if(m_source != null) {
            this.setValue(m_source.getPercentile(tStart, tEnd, m_percentile));
        }
    }

    /**
     * Takes the given value and puts it in each position in the 'values' array.
     * @param value
     */
    private void setValue(double value) {
        this.m_value = value;
        long[] times = getTimestamps();
        if( times != null ) {
            int count = times.length;
            double[] values = new double[count];
            for (int i = 0; i < count; i++) {
                    values[i] = m_value;
            }
            setValues(values);
        }
    }

    @Override
    void setTimestamps(long[] timestamps) {
        super.setTimestamps(timestamps);
        //And now also call setValue with the current value, to sort out "values"
        setValue(m_value);
    }

    /**
     * Same as SDef; the aggregates of a static value are all just the
     * same static value.
     *
     * Assumes this def has been realized by calling calculate(), otherwise
     * the aggregated values will be NaN
     */
    @Override
    Aggregates getAggregates(long tStart, long tEnd) throws RrdException {
        Aggregates agg = new Aggregates();
        agg.first = agg.last = agg.min = agg.max = agg.average = m_value;
        agg.total = m_value * (tEnd - tStart);
        return agg;
    }

    /**
     * Returns just the calculated percentile; the "Xth" percentile of a static value is
     * the static value itself.
     *
     * Assumes this def has been realized by calling calculate(), otherwise
     * the aggregated values will be NaN
     */
    @Override
    double getPercentile(long tStart, long tEnd, double percentile)
            throws RrdException {
        return m_value;
    }

}
