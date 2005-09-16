/*
 	launch4j :: Cross-platform Java application wrapper for creating Windows native executables
 	Copyright (C) 2005 Grzegorz Kowal

	This program is free software; you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation; either version 2 of the License, or
	(at your option) any later version.

	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with this program; if not, write to the Free Software
	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
*/

/*
 * Created on Apr 30, 2005
 */
package net.sf.launch4j.binding;

import java.awt.Color;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public interface Binding {
	/** Used to mark components with invalid data. */
	public final static Color INVALID_COLOR = Color.PINK;

	/** Java Bean property bound to a component */
	public String getProperty();
	/** Clear component, set it to the default value. */
	public void clear();
	/** Java Bean property -> Component */
	public void put(IValidatable bean);
	/** Component -> Java Bean property */
	public void get(IValidatable bean);
	/** Mark component as valid */
	public void markValid();
	/** Mark component as invalid */
	public void markInvalid();
	/** Enable or disable the component */
	public void setEnabled(boolean enabled);
}
