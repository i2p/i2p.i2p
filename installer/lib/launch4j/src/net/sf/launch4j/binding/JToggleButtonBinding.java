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

import javax.swing.JToggleButton;

import org.apache.commons.beanutils.PropertyUtils;

/**
 * Handles JToggleButton, JCheckBox 
 *
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class JToggleButtonBinding implements Binding {
	private final String _property;
	private final JToggleButton _button;
	private final boolean _defaultValue;
	private final Color _validColor;

	public JToggleButtonBinding(String property, JToggleButton button, boolean defaultValue) {
		if (property == null || button == null) {
			throw new NullPointerException();
		}
		if (property.equals("")) {
			throw new IllegalArgumentException();
		}
		_property = property;
		_button = button;
		_defaultValue = defaultValue;
		_validColor = _button.getBackground();
	}

	public String getProperty() {
		return _property;
	}

	public void clear() {
		_button.setSelected(_defaultValue);
	}

	public void put(IValidatable bean) {
		try {
			// String s = BeanUtils.getProperty(o, _property);
			// _checkBox.setSelected("true".equals(s));
			Boolean b = (Boolean) PropertyUtils.getProperty(bean, _property);
			_button.setSelected(b != null && b.booleanValue());
		} catch (Exception e) {
			throw new BindingException(e);
		}
	}

	public void get(IValidatable bean) {
		try {
			PropertyUtils.setProperty(bean, _property, Boolean.valueOf(_button.isSelected()));
		} catch (Exception e) {
			throw new BindingException(e);
		}
	}
	
	public void markValid() {
		_button.setBackground(_validColor);
		_button.requestFocusInWindow();
	}

	public void markInvalid() {
		_button.setBackground(Binding.INVALID_COLOR);
	}
	
	public void setEnabled(boolean enabled) {
		_button.setEnabled(enabled);
	}
}
