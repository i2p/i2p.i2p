package net.i2p.router.news;

/******************************************************************
*  Contains code modified from JaxpParser:
*
*    CyberXML for Java
*
*    Copyright (C) Satoshi Konno 2004
*
*    Author: Markus Thurner (http://thoean.com)
*
*  Contains code modified from Node:
*
*    CyberXML for Java
*
*    Copyright (C) Satoshi Konno 2002
******************************************************************/

import org.w3c.dom.NamedNodeMap;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import org.cybergarage.xml.Attribute;
import org.cybergarage.xml.Node;
import org.cybergarage.xml.XML;
import org.cybergarage.xml.parser.JaxpParser;


/**
 *  Override so that XHTML is parsed correctly.
 *
 *  This requires us to maintain mixed text and subnodes and output both.
 *
 *  @since 0.9.17
 */
public class XMLParser extends JaxpParser {
    private final Log _log;

    public static final String TEXT_NAME = "#text";

    public XMLParser(I2PAppContext ctx) {
        super();
        _log = ctx.logManager().getLog(XMLParser.class);
    }

    /**
     *  Modified from UPnP JaxpParser
     *
     *  @param parentNode null if at top
     *  @param rank parse level, only for debug
     *  @return the parsed node, or the parent node, unused except at top level
     */
    @Override
    public org.cybergarage.xml.Node parse(Node parentNode, org.w3c.dom.Node domNode, int rank) {
        int domNodeType = domNode.getNodeType();
        String domNodeName = domNode.getNodeName();
        String domNodeValue = domNode.getNodeValue();
        NamedNodeMap attrs = domNode.getAttributes(); 
        int arrrsLen = (attrs != null) ? attrs.getLength() : 0;

        if (_log.shouldLog(Log.DEBUG)) {
            String val = domNodeValue != null ?
                         " = \"" + domNodeValue.replace("\n", "\\n").replace("\r", "\\r") + '"' :
                         "";
            _log.debug("[" + rank + "] ELEM : \"" + domNodeName + '"' + val +
                       " type = " + domNodeType + " with " + arrrsLen + " attrs");
        }

        // I2P -
        // If it's only whitespace, skip it altogether.
        // Only add it to the value if we don't have any other nodes.
        // Otherwise, add it as a node.
        if (domNodeType == org.w3c.dom.Node.TEXT_NODE) {
            if (domNodeValue.replaceAll("[ \t\r\n]", "").length() == 0) {
                return parentNode;
            }
            if (!parentNode.hasNodes()) {
                parentNode.addValue(domNodeValue);
                return parentNode;
            }
            // else we will add it as a node below
        } else if (domNodeType != org.w3c.dom.Node.ELEMENT_NODE) {
            return parentNode;
        }

        Node node = new Node();
        node.setName(domNodeName);
        node.setValue(domNodeValue);

        if (parentNode != null) {
            // I2P - take the value and convert it to a text node, if it's not just whitespace
            String oldValue = parentNode.getValue();
            if (oldValue != null && oldValue.length() > 0) {
                parentNode.setValue("");
                Node text = new Node();
                text.setName(TEXT_NAME);
                text.setValue(oldValue);
                parentNode.addNode(text);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Converted value to node");
            }
            parentNode.addNode(node);
        }
        if (domNodeType == org.w3c.dom.Node.TEXT_NODE)
            return parentNode;

        if (attrs != null) {
            for (int n = 0; n < arrrsLen; n++) {
                org.w3c.dom.Node attr = attrs.item(n);
                String attrName = attr.getNodeName();
                String attrValue = attr.getNodeValue();
                node.setAttribute(attrName, attrValue);
            }
        }
        
        org.w3c.dom.Node child = domNode.getFirstChild();
        if (child == null) { 
            node.setValue(""); 
            return node; 
        }
        do{
            parse(node, child, rank+1);
            child = child.getNextSibling();
        } while (child != null);        
        
        return node;
    }

    /**
     *  A replacement for Node.toString(), which does not recognize #text.
     */
    public static void toString(StringBuilder buf, Node node) {
        output(buf, node, 0);
    }


    /**
     *  A replacement for Node.output(), which does not recognize #text.
     *  Also, we use the empty entity, so <br /> does not turn into <br></br>.
     */
    private static void output(StringBuilder buf, Node node, int indentLevel) {
        String name = node.getName();
        String value = XML.escapeXMLChars(node.getValue());
        if (name.equals(TEXT_NAME)) {
            buf.append(value);
            return;
        }
        
        buf.append('<').append(name);
        int nAttributes = node.getNAttributes();
        for (int n = 0; n < nAttributes; n++) {
            Attribute attr = node.getAttribute(n);
            buf.append(' ').append(attr.getName()).append("=\"").append(XML.escapeXMLChars(attr.getValue())).append('"');
        }

        // As in Node, output either the nodes or the value.
        // If mixed values and nodes, the values must be text nodes. See parser above.
        if (node.hasNodes()) {        
            buf.append('>');
            int nChildNodes = node.getNNodes();
            for (int n = 0; n < nChildNodes; n++) {
                Node cnode = node.getNode(n);
                output(buf, cnode, indentLevel + 1);
            }
            buf.append("</").append(name).append('>');
        } else {
            if (value == null || value.length() == 0) {
                // space for <br />
                buf.append(" />");
            } else {
                buf.append('>').append(value).append("</").append(name).append('>');
            }
        }
    }
}
