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

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JRadioButton;
import javax.swing.JToggleButton;
import javax.swing.text.JTextComponent;

import org.apache.commons.beanutils.PropertyUtils;

/**
 * Creates and handles bindings.
 * 
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class Bindings implements PropertyChangeListener {
	private final Map _bindings = new HashMap();
	private final Map _optComponents = new HashMap();
	private boolean _modified = false;

	/**
	 * Used to track component modifications.
	 */
	public void propertyChange(PropertyChangeEvent evt) {
		if ("AccessibleValue".equals(evt.getPropertyName())
				|| "AccessibleText".equals(evt.getPropertyName())) {
			_modified = true;
		}
	}

	/** 
	 * Any of the components modified?
	 */
	public boolean isModified() {
		return _modified;
	}

	public Binding getBinding(String property) {
		return (Binding) _bindings.get(property);
	}

	private void registerPropertyChangeListener(JComponent c) {
		c.getAccessibleContext().addPropertyChangeListener(this);
	}

	private void registerPropertyChangeListener(JComponent[] cs) {
		for (int i = 0; i < cs.length; i++) {
			cs[i].getAccessibleContext().addPropertyChangeListener(this);
		}
	}

	private boolean isPropertyNull(IValidatable bean, Binding b) {
		try {
			for (Iterator iter = _optComponents.keySet().iterator(); iter.hasNext();) {
				String property = (String) iter.next();
				if (b.getProperty().startsWith(property)) {
					return PropertyUtils.getProperty(bean, property) == null;
				}
			}
			return false;
		} catch (Exception e) {
			throw new BindingException(e);
		}
	}

	/**
	 * Enables or disables all components bound to properties that begin with given prefix.
	 */
	public void setComponentsEnabled(String prefix, boolean enabled) {
		for (Iterator iter = _bindings.values().iterator(); iter.hasNext();) {
			Binding b = (Binding) iter.next();
			if (b.getProperty().startsWith(prefix)) {
				b.setEnabled(enabled);
			}
		}
	}

	/**
	 * Clear all components, set them to their default values.
	 * Clears the _modified flag.
	 */
	public void clear() {
		for (Iterator iter = _optComponents.values().iterator(); iter.hasNext();) {
			((Binding) iter.next()).clear();
		}
		for (Iterator iter = _bindings.values().iterator(); iter.hasNext();) {
			((Binding) iter.next()).clear();
		}
		_modified = false;
	}

	/**
	 * Copies data from the Java Bean to the UI components.
	 * Clears the _modified flag.
	 */
	public void put(IValidatable bean) {
		for (Iterator iter = _optComponents.values().iterator(); iter.hasNext();) {
			((Binding) iter.next()).put(bean);
		}
		for (Iterator iter = _bindings.values().iterator(); iter.hasNext();) {
			Binding b = (Binding) iter.next();
			if (isPropertyNull(bean, b)) {
				b.clear();
			} else {
				b.put(bean);
			}
		}
		_modified = false;
	}

	/**
	 * Copies data from UI components to the Java Bean and checks it's class invariants.
	 * Clears the _modified flag.
	 * @throws InvariantViolationException
	 * @throws BindingException
	 */
	public void get(IValidatable bean) {
		try {
			for (Iterator iter = _optComponents.values().iterator(); iter.hasNext();) {
				((Binding) iter.next()).get(bean);
			}
			for (Iterator iter = _bindings.values().iterator(); iter.hasNext();) {
				Binding b = (Binding) iter.next();
				if (!isPropertyNull(bean, b)) {
					b.get(bean);
				}
			}
			bean.checkInvariants();
			for (Iterator iter = _optComponents.keySet().iterator(); iter.hasNext();) {
				String property = (String) iter.next();
				IValidatable component = (IValidatable) PropertyUtils.getProperty(bean, property);
				if (component != null) {
					component.checkInvariants();
				}
			}
			_modified = false;	// XXX
		} catch (InvariantViolationException e) {
			e.setBinding(getBinding(e.getProperty()));
			throw e;
		} catch (Exception e) {
			throw new BindingException(e);
		} 
	}

	private Bindings add(Binding b) {
		if (_bindings.containsKey(b.getProperty())) {
			throw new BindingException("Duplicate binding");
		}
		_bindings.put(b.getProperty(), b);
		return this;
	}

	/**
	 * Add an optional (nullable) Java Bean component of type clazz.
	 */
	public Bindings addOptComponent(String property, Class clazz, JToggleButton c,
			boolean enabledByDefault) {
		Binding b = new OptComponentBinding(this, property, clazz, c, enabledByDefault);
		if (_optComponents.containsKey(property)) {
			throw new BindingException("Duplicate binding");
		}
		_optComponents.put(property, b);
		return this;
	}

	/**
	 * Add an optional (nullable) Java Bean component of type clazz.
	 */
	public Bindings addOptComponent(String property, Class clazz, JToggleButton c) {
		return addOptComponent(property, clazz, c, false);
	}

	/**
	 * Handles JEditorPane, JTextArea, JTextField
	 */
	public Bindings add(String property, JTextComponent c, String defaultValue) {
		registerPropertyChangeListener(c);
		return add(new JTextComponentBinding(property, c, defaultValue));
	}

	/**
	 * Handles JEditorPane, JTextArea, JTextField
	 */
	public Bindings add(String property, JTextComponent c) {
		registerPropertyChangeListener(c);
		return add(new JTextComponentBinding(property, c, ""));
	}

	/**
	 * Handles JToggleButton, JCheckBox 
	 */
	public Bindings add(String property, JToggleButton c, boolean defaultValue) {
		registerPropertyChangeListener(c);
		return add(new JToggleButtonBinding(property, c, defaultValue));
	}

	/**
	 * Handles JToggleButton, JCheckBox
	 */
	public Bindings add(String property, JToggleButton c) {
		registerPropertyChangeListener(c);
		return add(new JToggleButtonBinding(property, c, false));
	}

	/**
	 * Handles JRadioButton
	 */
	public Bindings add(String property, JRadioButton[] cs, int defaultValue) {
		registerPropertyChangeListener(cs);
		return add(new JRadioButtonBinding(property, cs, defaultValue));
	}
	
	/**
	 * Handles JRadioButton
	 */
	public Bindings add(String property, JRadioButton[] cs) {
		registerPropertyChangeListener(cs);
		return add(new JRadioButtonBinding(property, cs, 0));
	}
}
