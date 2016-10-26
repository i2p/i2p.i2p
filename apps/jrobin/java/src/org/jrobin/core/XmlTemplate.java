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

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class used as a base class for various XML template related classes. Class provides
 * methods for XML source parsing and XML tree traversing. XML source may have unlimited
 * number of placeholders (variables) in the format <code>${variable_name}</code>.
 * Methods are provided to specify variable values at runtime.
 * Note that this class has limited functionality: XML source gets parsed, and variable
 * values are collected. You have to extend this class to do something more useful.<p>
 */
public abstract class XmlTemplate {
	private static final String PATTERN_STRING = "\\$\\{(\\w+)\\}";
	private static final Pattern PATTERN = Pattern.compile(PATTERN_STRING);

	protected Element root;
	private HashMap<String, Object> valueMap = new HashMap<String, Object>();
	private HashSet<Node> validatedNodes = new HashSet<Node>();

	protected XmlTemplate(InputSource xmlSource) throws IOException, RrdException {
		root = Util.Xml.getRootElement(xmlSource);
	}

	protected XmlTemplate(String xmlString) throws IOException, RrdException {
		root = Util.Xml.getRootElement(xmlString);
	}

	protected XmlTemplate(File xmlFile) throws IOException, RrdException {
		root = Util.Xml.getRootElement(xmlFile);
	}

	/**
	 * Removes all placeholder-value mappings.
	 */
	public void clearValues() {
		valueMap.clear();
	}

	/**
	 * Sets value for a single XML template variable. Variable name should be specified
	 * without leading '${' and ending '}' placeholder markers. For example, for a placeholder
	 * <code>${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
	 *
	 * @param name  variable name
	 * @param value value to be set in the XML template
	 */
	public void setVariable(String name, String value) {
		valueMap.put(name, value);
	}

	/**
	 * Sets value for a single XML template variable. Variable name should be specified
	 * without leading '${' and ending '}' placeholder markers. For example, for a placeholder
	 * <code>${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
	 *
	 * @param name  variable name
	 * @param value value to be set in the XML template
	 */
	public void setVariable(String name, int value) {
		valueMap.put(name, value);
	}

	/**
	 * Sets value for a single XML template variable. Variable name should be specified
	 * without leading '${' and ending '}' placeholder markers. For example, for a placeholder
	 * <code>${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
	 *
	 * @param name  variable name
	 * @param value value to be set in the XML template
	 */
	public void setVariable(String name, long value) {
		valueMap.put(name, value);
	}

	/**
	 * Sets value for a single XML template variable. Variable name should be specified
	 * without leading '${' and ending '}' placeholder markers. For example, for a placeholder
	 * <code>${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
	 *
	 * @param name  variable name
	 * @param value value to be set in the XML template
	 */
	public void setVariable(String name, double value) {
		valueMap.put(name, value);
	}

	/**
	 * Sets value for a single XML template variable. Variable name should be specified
	 * without leading '${' and ending '}' placeholder markers. For example, for a placeholder
	 * <code>${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
	 *
	 * @param name  variable name
	 * @param value value to be set in the XML template
	 */
	public void setVariable(String name, Color value) {
		String r = byteToHex(value.getRed());
		String g = byteToHex(value.getGreen());
		String b = byteToHex(value.getBlue());
		String a = byteToHex(value.getAlpha());
		valueMap.put(name, "#" + r + g + b + a);
	}

	private String byteToHex(int i) {
		String s = Integer.toHexString(i);
		while (s.length() < 2) {
			s = "0" + s;
		}
		return s;
	}

	/**
	 * Sets value for a single XML template variable. Variable name should be specified
	 * without leading '${' and ending '}' placeholder markers. For example, for a placeholder
	 * <code>${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
	 *
	 * @param name  variable name
	 * @param value value to be set in the XML template
	 */
	public void setVariable(String name, Date value) {
		setVariable(name, Util.getTimestamp(value));
	}

	/**
	 * Sets value for a single XML template variable. Variable name should be specified
	 * without leading '${' and ending '}' placeholder markers. For example, for a placeholder
	 * <code>${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
	 *
	 * @param name  variable name
	 * @param value value to be set in the XML template
	 */
	public void setVariable(String name, Calendar value) {
		setVariable(name, Util.getTimestamp(value));
	}

	/**
	 * Sets value for a single XML template variable. Variable name should be specified
	 * without leading '${' and ending '}' placeholder markers. For example, for a placeholder
	 * <code>${start}</code>, specify <code>start</code> for the <code>name</code> parameter.
	 *
	 * @param name  variable name
	 * @param value value to be set in the XML template
	 */
	public void setVariable(String name, boolean value) {
		valueMap.put(name, "" + value);
	}

	/**
	 * Searches the XML template to see if there are variables in there that
	 * will need to be set.
	 *
	 * @return True if variables were detected, false if not.
	 */
	public boolean hasVariables() {
		return PATTERN.matcher(root.toString()).find();
	}

	/**
	 * Returns the list of variables that should be set in this template.
	 *
	 * @return List of variable names as an array of strings.
	 */
	public String[] getVariables() {
		ArrayList<String> list = new ArrayList<String>();
		Matcher m = PATTERN.matcher(root.toString());

		while (m.find()) {
			String var = m.group(1);
			if (!list.contains(var)) {
				list.add(var);
			}
		}

		return list.toArray(new String[list.size()]);
	}

	protected static Node[] getChildNodes(Node parentNode, String childName) {
		return Util.Xml.getChildNodes(parentNode, childName);
	}

	protected static Node[] getChildNodes(Node parentNode) {
		return Util.Xml.getChildNodes(parentNode, null);
	}

	protected static Node getFirstChildNode(Node parentNode, String childName) throws RrdException {
		return Util.Xml.getFirstChildNode(parentNode, childName);
	}

	protected boolean hasChildNode(Node parentNode, String childName) {
		return Util.Xml.hasChildNode(parentNode, childName);
	}

	protected String getChildValue(Node parentNode, String childName) throws RrdException {
		return getChildValue(parentNode, childName, true);
	}

	protected String getChildValue(Node parentNode, String childName, boolean trim) throws RrdException {
		String value = Util.Xml.getChildValue(parentNode, childName, trim);
		return resolveMappings(value);
	}

	protected String getValue(Node parentNode) {
		return getValue(parentNode, true);
	}

	protected String getValue(Node parentNode, boolean trim) {
		String value = Util.Xml.getValue(parentNode, trim);
		return resolveMappings(value);
	}

	private String resolveMappings(String templateValue) {
		if (templateValue == null) {
			return null;
		}
		Matcher matcher = PATTERN.matcher(templateValue);
		StringBuffer result = new StringBuffer();
		int lastMatchEnd = 0;
		while (matcher.find()) {
			String var = matcher.group(1);
			if (valueMap.containsKey(var)) {
				// mapping found
				result.append(templateValue.substring(lastMatchEnd, matcher.start()));
				result.append(valueMap.get(var).toString());
				lastMatchEnd = matcher.end();
			}
			else {
				// no mapping found - this is illegal
				// throw runtime exception
				throw new IllegalArgumentException("No mapping found for template variable ${" + var + "}");
			}
		}
		result.append(templateValue.substring(lastMatchEnd));
		return result.toString();
	}

	protected int getChildValueAsInt(Node parentNode, String childName) throws RrdException {
		String valueStr = getChildValue(parentNode, childName);
		return Integer.parseInt(valueStr);
	}

	protected int getValueAsInt(Node parentNode) {
		String valueStr = getValue(parentNode);
		return Integer.parseInt(valueStr);
	}

	protected long getChildValueAsLong(Node parentNode, String childName) throws RrdException {
		String valueStr = getChildValue(parentNode, childName);
		return Long.parseLong(valueStr);
	}

	protected long getValueAsLong(Node parentNode) {
		String valueStr = getValue(parentNode);
		return Long.parseLong(valueStr);
	}

	protected double getChildValueAsDouble(Node parentNode, String childName) throws RrdException {
		String valueStr = getChildValue(parentNode, childName);
		return Util.parseDouble(valueStr);
	}

	protected double getValueAsDouble(Node parentNode) {
		String valueStr = getValue(parentNode);
		return Util.parseDouble(valueStr);
	}

	protected boolean getChildValueAsBoolean(Node parentNode, String childName) throws RrdException {
		String valueStr = getChildValue(parentNode, childName);
		return Util.parseBoolean(valueStr);
	}

	protected boolean getValueAsBoolean(Node parentNode) {
		String valueStr = getValue(parentNode);
		return Util.parseBoolean(valueStr);
	}

	protected Paint getValueAsColor(Node parentNode) throws RrdException {
		String rgbStr = getValue(parentNode);
		return Util.parseColor(rgbStr);
	}

	protected boolean isEmptyNode(Node node) {
		// comment node or empty text node
		return node.getNodeName().equals("#comment") ||
				(node.getNodeName().equals("#text") && node.getNodeValue().trim().length() == 0);
	}

	protected void validateTagsOnlyOnce(Node parentNode, String[] allowedChildNames) throws RrdException {
		// validate node only once
		if (validatedNodes.contains(parentNode)) {
			return;
		}
		Node[] childs = getChildNodes(parentNode);
		main:
		for (Node child : childs) {
			String childName = child.getNodeName();
			for (int j = 0; j < allowedChildNames.length; j++) {
				if (allowedChildNames[j].equals(childName)) {
					// only one such tag is allowed
					allowedChildNames[j] = "<--removed-->";
					continue main;
				}
				else if (allowedChildNames[j].equals(childName + "*")) {
					// several tags allowed
					continue main;
				}
			}
			if (!isEmptyNode(child)) {
				throw new RrdException("Unexpected tag encountered: <" + childName + ">");
			}
		}
		// everything is OK
		validatedNodes.add(parentNode);
	}
}
