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
 * Created on May 10, 2005
 */
package net.sf.launch4j.binding;

import java.awt.Color;

import javax.swing.JRadioButton;

import org.apache.commons.beanutils.PropertyUtils;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class JRadioButtonBinding implements Binding {
	private final String _property;
	private final JRadioButton[] _buttons;
	private final int _defaultValue;
	private final Color _validColor;

	public JRadioButtonBinding(String property, JRadioButton[] buttons, int defaultValue) {
		if (property == null || buttons == null) {
			throw new NullPointerException();
		}
		for (int i = 0; i < buttons.length; i++) {
			if (buttons[i] == null) {
				throw new NullPointerException();
			}
		}
		if (property.equals("")
				|| buttons.length == 0
				|| defaultValue < 0 || defaultValue >= buttons.length) {
			throw new IllegalArgumentException();
		}
		_property = property;
		_buttons = buttons;
		_defaultValue = defaultValue;
		_validColor = buttons[0].getBackground();
	}

	public String getProperty() {
		return _property;
	}

	public void clear() {
		select(_defaultValue);
	}

	public void put(IValidatable bean) {
		try {
			Integer i = (Integer) PropertyUtils.getProperty(bean, _property);
			if (i == null) {
				throw new BindingException("Property is null");
			}
			select(i.intValue());
		} catch (Exception e) {
			throw new BindingException(e);
		}
	}

	public void get(IValidatable bean) {
		try {
			for (int i = 0; i < _buttons.length; i++) {
				if (_buttons[i].isSelected()) {
					PropertyUtils.setProperty(bean, _property, new Integer(i));
					return;
				}
			}
			throw new BindingException("Nothing selected");
		} catch (Exception e) {
			throw new BindingException(e);
		}
	}

	private void select(int index) {
		if (index < 0 || index >= _buttons.length) {
			throw new BindingException("Button index out of bounds");
		}
		_buttons[index].setSelected(true);
	}

	public void markValid() {
		for (int i = 0; i < _buttons.length; i++) {
			if (_buttons[i].isSelected()) {
				_buttons[i].setBackground(_validColor);
				_buttons[i].requestFocusInWindow();
				return;
			}
		}
		throw new BindingException("Nothing selected");
	}

	public void markInvalid() {
		for (int i = 0; i < _buttons.length; i++) {
			if (_buttons[i].isSelected()) {
				_buttons[i].setBackground(Binding.INVALID_COLOR);
				return;
			}
		}
		throw new BindingException("Nothing selected");
	}
	
	public void setEnabled(boolean enabled) {
		for (int i = 0; i < _buttons.length; i++) {
			_buttons[i].setEnabled(enabled);
		}
	}
}
