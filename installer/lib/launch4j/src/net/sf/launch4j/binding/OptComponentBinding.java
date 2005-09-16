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
 * Created on May 11, 2005
 */
package net.sf.launch4j.binding;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Arrays;

import javax.swing.JToggleButton;

import org.apache.commons.beanutils.PropertyUtils;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class OptComponentBinding implements Binding, ActionListener {
	private final Bindings _bindings;
	private final String _property;
	private final Class _clazz;
	private final JToggleButton _button;
	private final boolean _enabledByDefault;

	public OptComponentBinding(Bindings bindings, String property, Class clazz,
								JToggleButton button, boolean enabledByDefault) {
		if (property == null || clazz == null || button == null) {
			throw new NullPointerException();
		}
		if (property.equals("")) {
			throw new IllegalArgumentException();
		}
		if (!Arrays.asList(clazz.getInterfaces()).contains(IValidatable.class)) {
			throw new IllegalArgumentException("Optional component must implement "
					+ IValidatable.class);
		}
		_bindings = bindings;
		_property = property;
		_clazz = clazz;
		_button = button;
		_button.addActionListener(this);
		_enabledByDefault = enabledByDefault;
	}

	public String getProperty() {
		return _property;
	}

	public void clear() {
		_button.setSelected(_enabledByDefault);
		updateComponents();
	}

	public void put(IValidatable bean) {
		try {
			Object component = PropertyUtils.getProperty(bean, _property);
			_button.setSelected(component != null);
			updateComponents();
		} catch (Exception e) {
			throw new BindingException(e);
		}
	}

	public void get(IValidatable bean) {
		try {
			PropertyUtils.setProperty(bean, _property, _button.isSelected()
					? _clazz.newInstance() : null);
		} catch (Exception e) {
			throw new BindingException(e);
		}
	}

	public void markValid() {}

	public void markInvalid() {}

	public void setEnabled(boolean enabled) {} // XXX implement?

	public void actionPerformed(ActionEvent e) {
		updateComponents();
	}
	
	private void updateComponents() {
		_bindings.setComponentsEnabled(_property, _button.isSelected());
	}
}
