package org.rrd4j.core;

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
 * values are collected. You have to extend this class to do something more useful.
 */
public abstract class XmlTemplate {
    private static final String PATTERN_STRING = "\\$\\{(\\w+)\\}";
    private static final Pattern PATTERN = Pattern.compile(PATTERN_STRING);

    protected Element root;
    private HashMap<String, Object> valueMap = new HashMap<>();
    private HashSet<Node> validatedNodes = new HashSet<>();

    /**
     * <p>Constructor for XmlTemplate.</p>
     *
     * @param xmlSource a {@link org.xml.sax.InputSource} object.
     * @throws java.io.IOException if any.
     */
    protected XmlTemplate(InputSource xmlSource) throws IOException {
        root = Util.Xml.getRootElement(xmlSource);
    }

    /**
     * <p>Constructor for XmlTemplate.</p>
     *
     * @param xmlString a {@link java.lang.String} object.
     * @throws java.io.IOException if any.
     */
    protected XmlTemplate(String xmlString) throws IOException {
        root = Util.Xml.getRootElement(xmlString);
    }

    /**
     * <p>Constructor for XmlTemplate.</p>
     *
     * @param xmlFile a {@link java.io.File} object.
     * @throws java.io.IOException if any.
     */
    protected XmlTemplate(File xmlFile) throws IOException {
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
        valueMap.put(name, Integer.valueOf(value));
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
        valueMap.put(name, Long.valueOf(value));
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
        valueMap.put(name, Double.valueOf(value));
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
        StringBuilder s = new StringBuilder(Integer.toHexString(i));
        while (s.length() < 2) {
            s.insert(0, "0");
        }
        return s.toString();
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
        valueMap.put(name, Boolean.toString(value));
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
        ArrayList<String> list = new ArrayList<>();
        Matcher m = PATTERN.matcher(root.toString());

        while (m.find()) {
            String var = m.group(1);
            if (!list.contains(var)) {
                list.add(var);
            }
        }

        return list.toArray(new String[list.size()]);
    }

    /**
     * <p>getChildNodes.</p>
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @param childName a {@link java.lang.String} object.
     * @return an array of {@link org.w3c.dom.Node} objects.
     */
    protected static Node[] getChildNodes(Node parentNode, String childName) {
        return Util.Xml.getChildNodes(parentNode, childName);
    }

    /**
     * <p>getChildNodes.</p>
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @return an array of {@link org.w3c.dom.Node} objects.
     */
    protected static Node[] getChildNodes(Node parentNode) {
        return Util.Xml.getChildNodes(parentNode, null);
    }

    /**
     * <p>getFirstChildNode.</p>
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @param childName a {@link java.lang.String} object.
     * @return a {@link org.w3c.dom.Node} object.
     */
    protected static Node getFirstChildNode(Node parentNode, String childName) {
        return Util.Xml.getFirstChildNode(parentNode, childName);
    }

    /**
     * <p>hasChildNode.</p>
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @param childName a {@link java.lang.String} object.
     * @return a boolean.
     */
    protected boolean hasChildNode(Node parentNode, String childName) {
        return Util.Xml.hasChildNode(parentNode, childName);
    }

    /**
     * <p>getChildValue.</p>
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @param childName a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     */
    protected String getChildValue(Node parentNode, String childName) {
        return getChildValue(parentNode, childName, true);
    }

    /**
     * <p>getChildValue.</p>
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @param childName a {@link java.lang.String} object.
     * @param trim a boolean.
     * @return a {@link java.lang.String} object.
     */
    protected String getChildValue(Node parentNode, String childName, boolean trim) {
        String value = Util.Xml.getChildValue(parentNode, childName, trim);
        return resolveMappings(value);
    }

    /**
     * <p>getValue.</p>
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @return a {@link java.lang.String} object.
     */
    protected String getValue(Node parentNode) {
        return getValue(parentNode, true);
    }

    /**
     * <p>getValue.</p>
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @param trim a boolean.
     * @return a {@link java.lang.String} object.
     */
    protected String getValue(Node parentNode, boolean trim) {
        String value = Util.Xml.getValue(parentNode, trim);
        return resolveMappings(value);
    }

    private String resolveMappings(String templateValue) {
        if (templateValue == null) {
            return null;
        }
        Matcher matcher = PATTERN.matcher(templateValue);
        StringBuilder result = new StringBuilder();
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

    /**
     * getChildValueAsInt.
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @param childName a {@link java.lang.String} object.
     * @return a int.
     */
    protected int getChildValueAsInt(Node parentNode, String childName) {
        String valueStr = getChildValue(parentNode, childName);
        return Integer.parseInt(valueStr);
    }

    /**
     * getValueAsInt.
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @return a int.
     */
    protected int getValueAsInt(Node parentNode) {
        String valueStr = getValue(parentNode);
        return Integer.parseInt(valueStr);
    }

    /**
     * getChildValueAsLong.
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @param childName a {@link java.lang.String} object.
     * @return a long.
     */
    protected long getChildValueAsLong(Node parentNode, String childName) {
        String valueStr = getChildValue(parentNode, childName);
        return Long.parseLong(valueStr);
    }

    /**
     * getValueAsLong.
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @return a long.
     */
    protected long getValueAsLong(Node parentNode) {
        String valueStr = getValue(parentNode);
        return Long.parseLong(valueStr);
    }

    /**
     * getChildValueAsDouble.
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @param childName a {@link java.lang.String} object.
     * @return a double.
     */
    protected double getChildValueAsDouble(Node parentNode, String childName) {
        String valueStr = getChildValue(parentNode, childName);
        return Util.parseDouble(valueStr);
    }

    /**
     * getValueAsDouble.
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @return a double.
     */
    protected double getValueAsDouble(Node parentNode) {
        String valueStr = getValue(parentNode);
        return Util.parseDouble(valueStr);
    }

    /**
     * getChildValueAsBoolean.
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @param childName a {@link java.lang.String} object.
     * @return a boolean.
     */
    protected boolean getChildValueAsBoolean(Node parentNode, String childName) {
        String valueStr = getChildValue(parentNode, childName);
        return Util.parseBoolean(valueStr);
    }

    /**
     * getValueAsBoolean.
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @return a boolean.
     */
    protected boolean getValueAsBoolean(Node parentNode) {
        String valueStr = getValue(parentNode);
        return Util.parseBoolean(valueStr);
    }

    /**
     * getValueAsColor.
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @return a {@link java.awt.Paint} object.
     */
    protected Paint getValueAsColor(Node parentNode) {
        String rgbStr = getValue(parentNode);
        return Util.parseColor(rgbStr);
    }

    /**
     * isEmptyNode.
     *
     * @param node a {@link org.w3c.dom.Node} object.
     * @return a boolean.
     */
    protected boolean isEmptyNode(Node node) {
        // comment node or empty text node
        return node.getNodeName().equals("#comment") ||
                (node.getNodeName().equals("#text") && node.getNodeValue().trim().length() == 0);
    }

    /**
     * validateTagsOnlyOnce.
     *
     * @param parentNode a {@link org.w3c.dom.Node} object.
     * @param allowedChildNames an array of {@link java.lang.String} objects.
     */
    protected void validateTagsOnlyOnce(Node parentNode, String[] allowedChildNames) {
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
                    throw new IllegalArgumentException("Unexpected tag encountered: <" + childName + ">");
                }
            }
        // everything is OK
        validatedNodes.add(parentNode);
    }
}
