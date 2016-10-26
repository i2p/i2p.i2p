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

/**
 * Class to represent various JRobin checked exceptions.
 * JRobin code can throw only <code>RrdException</code>
 * (for various JRobin related errors) or <code>IOException</code>
 * (for various I/O errors).
 *
 * @author <a href="mailto:saxon@jrobin.org">Sasa Markovic</a>
 */
public class RrdException extends Exception {
    private static final long serialVersionUID = 6999702149227009855L;

    public RrdException() {
	    super();
	}

	public RrdException(final String message) {
		super(message);
	}

	public RrdException(final Throwable cause) {
		super(cause);
	}

	public RrdException(final String message, final Throwable cause) {
	    super(message, cause);
	}
}
