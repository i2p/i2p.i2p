/******************************************************************
*
*	CyberXML for Java
*
*	Copyright (C) Satoshi Konno 2004
*
*   Author: Markus Thurner (http://thoean.com)
*
*	File: JaxpParser.java
*
*	Revision;
*
*	06/15/04
*		- first revision.
*
******************************************************************/

package org.cybergarage.xml.parser;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.cybergarage.xml.Node;
import org.cybergarage.xml.Parser;
import org.cybergarage.xml.ParserException;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.xml.sax.InputSource;


public class JaxpParser extends Parser
{

	public JaxpParser()
	{
		super();
	}
	
	////////////////////////////////////////////////
	//	parse (Node)
	////////////////////////////////////////////////

	public org.cybergarage.xml.Node parse(org.cybergarage.xml.Node parentNode, org.w3c.dom.Node domNode, int rank)
	{
		int domNodeType = domNode.getNodeType();
//		if (domNodeType != Node.ELEMENT_NODE)
//			return;
			
		String domNodeName = domNode.getNodeName();
		String domNodeValue = domNode.getNodeValue();

//		Debug.message("[" + rank + "] ELEM : " + domNodeName + ", " + domNodeValue + ", type = " + domNodeType + ", attrs = " + arrrsLen);

		if (domNodeType == org.w3c.dom.Node.TEXT_NODE) {
			parentNode.setValue(domNodeValue);
			return parentNode;
		}

		if (domNodeType != org.w3c.dom.Node.ELEMENT_NODE)
			return parentNode;

		org.cybergarage.xml.Node node = new org.cybergarage.xml.Node();
		node.setName(domNodeName);
		node.setValue(domNodeValue);

		if (parentNode != null)
			parentNode.addNode(node);

		NamedNodeMap attrMap = domNode.getAttributes(); 
		int attrLen = attrMap.getLength();
		//Debug.message("attrLen = " + attrLen);
		for (int n = 0; n<attrLen; n++) {
			org.w3c.dom.Node attr = attrMap.item(n);
			String attrName = attr.getNodeName();
			String attrValue = attr.getNodeValue();
			node.setAttribute(attrName, attrValue);
		}
		
		org.w3c.dom.Node child = domNode.getFirstChild();
		while (child != null) {
			parse(node, child, rank+1);
			child = child.getNextSibling();
		}
		
		return node;
	}

	public org.cybergarage.xml.Node parse(org.cybergarage.xml.Node parentNode, org.w3c.dom.Node domNode)
	{
		return parse(parentNode, domNode, 0);
	}

	/* (non-Javadoc)
	 * @see org.cybergarage.xml.Parser#parse(java.io.InputStream)
	 */
	public Node parse(InputStream inStream) throws ParserException
	{
		org.cybergarage.xml.Node root = null;
		
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			InputSource inSrc = new InputSource(new NullFilterInputStream(inStream));
			Document doc = builder.parse(inSrc);

			org.w3c.dom.Element docElem = doc.getDocumentElement();

			if (docElem != null)
				root = parse(root, docElem);
/*
			NodeList rootList = doc.getElementsByTagName("root");
			Debug.message("rootList = " + rootList.getLength());
			
			if (0 < rootList.getLength())
				root = parse(root, rootList.item(0));
*/
		}
		catch (Exception e) {
			throw new ParserException(e);
		}
		
		return root;
	}

	/**
	 *  I2P -
	 *  Filter out nulls, hopefully to avoid
	 *  SAXParserException "Content not allowed in trailing section",
	 *  which is apparently caused by nulls.
	 *  Alternative is to remove all stuff between '>' and '<',
         *  which isn't so hard if we assume no CDATA.
	 */
	private static class NullFilterInputStream extends FilterInputStream {

		public NullFilterInputStream(InputStream is) {
			super(is);
		}

		@Override
		public int read() throws IOException {
			int rv;
			while ((rv = super.read()) == 0) {
				// try again
			}
			return rv;
		}
	}
}
