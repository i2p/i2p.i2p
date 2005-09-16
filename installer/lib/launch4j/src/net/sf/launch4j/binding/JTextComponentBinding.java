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

import javax.swing.text.JTextComponent;

import org.apache.commons.beanutils.BeanUtils;

/**
 * Handles JEditorPane, JTextArea, JTextField
 * 
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class JTextComponentBinding implements Binding {
	private final String _property;
	private final JTextComponent _textComponent;
	private final String _defaultValue;
	private final Color _validColor;

	public JTextComponentBinding(String property, JTextComponent textComponent, String defaultValue) {
		if (property == null || textComponent == null || defaultValue == null) {
			throw new NullPointerException();
		}
		if (property.equals("")) {
			throw new IllegalArgumentException();
		}
		_property = property;
		_textComponent = textComponent;
		_defaultValue = defaultValue;
		_validColor = _textComponent.getBackground();
	}

	public String getProperty() {
		return _property;
	}

	public void clear() {
		_textComponent.setText(_defaultValue);
	}

	public void put(IValidatable bean) {
		try {
			String s = BeanUtils.getProperty(bean, _property);
			_textComponent.setText(s != null && !s.equals("0") ? s : "");	// XXX displays zeros as blank
		} catch (Exception e) {
			throw new BindingException(e);
		}
	}

	public void get(IValidatable bean) {
		try {
			BeanUtils.setProperty(bean, _property, _textComponent.getText());
		} catch (Exception e) {
			throw new BindingException(e);
		}
	}
	
	public void markValid() {
		_textComponent.setBackground(_validColor);
		_textComponent.requestFocusInWindow();
	}

	public void markInvalid() {
		_textComponent.setBackground(Binding.INVALID_COLOR);
	}
	
	public void setEnabled(boolean enabled) {
		_textComponent.setEnabled(enabled);
	}
}
