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

package org.jrobin.graph;

import org.jrobin.data.DataProcessor;

class Def extends Source {
	private final String rrdPath, dsName, consolFun, backend;

	Def(String name, String rrdPath, String dsName, String consolFun) {
		this(name, rrdPath, dsName, consolFun, null);
	}

	Def(String name, String rrdPath, String dsName, String consolFun, String backend) {
		super(name);
		this.rrdPath = rrdPath;
		this.dsName = dsName;
		this.consolFun = consolFun;
		this.backend = backend;
	}

	void requestData(DataProcessor dproc) {
		if (backend == null) {
			dproc.addDatasource(name, rrdPath, dsName, consolFun);
		}
		else {
			dproc.addDatasource(name, rrdPath, dsName, consolFun, backend);
		}
	}
}
