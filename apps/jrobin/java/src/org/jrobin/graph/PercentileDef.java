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
package org.jrobin.graph;

import org.jrobin.data.DataProcessor;
import org.jrobin.graph.Source;

public class PercentileDef extends Source {
    private String m_sourceName;

    private double m_percentile;

    private boolean m_includenan;

    PercentileDef(String name, String sourceName, double percentile) {
        this(name, sourceName, percentile, false);
    }

    PercentileDef(String name, String sourceName, double percentile, boolean includenan) {
        super(name);
        m_sourceName = sourceName;
        m_percentile = percentile;
        m_includenan = includenan;
    }

    @Override
    void requestData(DataProcessor dproc) {
        dproc.addDatasource(name, m_sourceName, m_percentile, m_includenan);
    }

}
